package com.scrollsoul.tracker;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReelsTrackerService extends AccessibilityService {

    private static final String TAG = "ScrollSoul.Service";
    private static final String IG = "com.instagram.android";
    private static final String PREFS = "scrollsoul_prefs";
    private static final String REELS_PAGER_ID = IG + ":id/clips_viewer_view_pager";

    // Short debounce on ViewPager page changes: rejects drag-and-pull-back without
    // losing fast consecutive swipes (a different page arriving flushes the pending one).
    private static final long PAGE_COMMIT_MS = 120;
    private static final long ENTRY_SETTLE_MS = 300;
    private static final long METADATA_REFRESH_MS = 320;
    private static final long CONTENT_CHECK_THROTTLE_MS = 200;

    private static final Pattern LIKE_NUM_PATTERN = Pattern.compile("([0-9]+(?:[.,][0-9]+)?\\s*[kKmMbB]?)");

    private static final class TrackedReel {
        final long id;
        String username = "";
        String caption = "";
        String likeCount = "";
        long accumulatedWatchMs = 0;
        double maxCompletion = 0.0;
        boolean liked = false;
        boolean commented = false;
        boolean shared = false;

        TrackedReel(long id) {
            this.id = id;
        }
    }

    private static final class ScrapedInfo {
        String username = "";
        String caption = "";
        String likeCount = "";
        boolean hasVideo = false;
    }

    private boolean isInReelsView = false;
    private long lastContentCheckMs = 0;

    // The service records reels straight into SQLite. JS timers are paused while
    // ScrollSoul is in the background, so recording cannot depend on the JS side.
    private TrackerDatabaseHelper db;
    private PowerManager powerManager;

    private TrackedReel activeReel = null;
    private TrackedReel lastExitedReel = null;
    private long activeSegmentStart = 0;
    private int currentPageIndex = -1;

    private int pendingPageIndex = -1;
    private long pendingPageStart = 0;
    private Runnable pendingPageCommit;
    private Runnable pendingEntrySettle;
    private Runnable pendingMetadataRefresh;

    private boolean lastSavedLiked, lastSavedCommented, lastSavedShared;
    private double lastSavedCompletion = -1;
    private long lastSavedWatchMs = -1;

    // Deduplication:
    // - pageToReelId prevents scrollback/rewatch within the active Reels pager from recounting.
    // - fingerprintToReelId prevents recounting when returning from profile/share/app switch.
    private final Map<Integer, Long> pageToReelId = new HashMap<>();
    private final LinkedHashMap<String, Long> fingerprintToReelId = new LinkedHashMap<String, Long>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 100;
        }
    };
    private final LinkedHashMap<Long, TrackedReel> reelStates = new LinkedHashMap<Long, TrackedReel>(160, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, TrackedReel> eldest) {
            return size() > 150;
        }
    };

    private boolean toggleMetadata = true;
    private boolean toggleWatchTime = true;
    private boolean toggleCompletion = true;
    private boolean toggleEngagement = true;
    private boolean toggleOcr = true;

    private Handler handler;
    private Runnable progressPoller;
    private static ReelsTrackerService sInstance;

    private WindowManager windowManager;
    private View overlayView;
    private TextView counterText;

    private final BroadcastReceiver screenOffReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction()) && isInReelsView) {
                Log.d(TAG, "Screen turned off, finalizing active reel");
                exitReels();
            }
        }
    };

    public static ReelsTrackerService getInstance() { return sInstance; }

    @Override public void onCreate() {
        super.onCreate();
        sInstance = this;
        handler = new Handler(Looper.getMainLooper());
        db = TrackerDatabaseHelper.getInstance(this);
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        loadToggles();
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenOffReceiver, filter);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to register screen off receiver", e);
        }
    }

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        initOverlay();
        emit("onServiceConnected", "{}");
    }

    @Override public void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(screenOffReceiver); } catch (Exception e) {}
        cancelAllPending();
        stopPolling();
        finalizeActiveReel(System.currentTimeMillis());
        sInstance = null;
        try { if (overlayView != null) windowManager.removeView(overlayView); } catch (Exception e) {}
    }

    @Override public void onInterrupt() {
        cancelAllPending();
        stopPolling();
    }

    public void clearSessionCache() {
        handler.post(() -> {
            cancelAllPending();
            pageToReelId.clear();
            fingerprintToReelId.clear();
            reelStates.clear();
            activeReel = null;
            lastExitedReel = null;
            currentPageIndex = -1;
            pendingPageIndex = -1;
            refreshOverlayCount();
        });
    }

    private void initOverlay() {
        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            overlayView = new TextView(this);
            counterText = (TextView) overlayView;

            GradientDrawable pillShape = new GradientDrawable();
            pillShape.setShape(GradientDrawable.RECTANGLE);
            pillShape.setCornerRadius(50f);
            pillShape.setColor(0xCC000000);

            counterText.setBackground(pillShape);
            counterText.setPadding(40, 20, 40, 20);
            counterText.setTextColor(0xFFFFFFFF);
            counterText.setTextSize(14);
            counterText.setGravity(Gravity.CENTER);
            counterText.setText("Reels: 0");
            counterText.setVisibility(View.GONE);

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            params.y = 80;
            windowManager.addView(overlayView, params);
        } catch (Exception e) {}
    }

    public void updateOverlayText(String text) {
        handler.post(() -> {
            try {
                if (counterText != null) {
                    counterText.setText(text);
                    counterText.setVisibility(isInReelsView ? View.VISIBLE : View.GONE);
                }
            } catch (Exception e) {}
        });
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            if (event == null) return;
            CharSequence pkg = event.getPackageName();
            if (pkg == null || !IG.equals(pkg.toString())) {
                String p = pkg != null ? pkg.toString() : "";
                if (isInReelsView
                        && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                        && !p.isEmpty()
                        && !"com.android.systemui".equals(p)) {
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    boolean stillInIg = false;
                    if (root != null) {
                        CharSequence rootPkg = root.getPackageName();
                        stillInIg = rootPkg != null && IG.equals(rootPkg.toString());
                        root.recycle();
                    }
                    if (!stillInIg) exitReels();
                }
                return;
            }

            switch (event.getEventType()) {
                case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                    checkReelsState();
                    break;
                case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED: {
                    long now = System.currentTimeMillis();
                    if (now - lastContentCheckMs >= CONTENT_CHECK_THROTTLE_MS) {
                        lastContentCheckMs = now;
                        checkReelsState();
                    }
                    break;
                }
                case AccessibilityEvent.TYPE_VIEW_SCROLLED:
                    if (!isInReelsView) checkReelsState();
                    if (isInReelsView) onScroll(event);
                    break;
                case AccessibilityEvent.TYPE_VIEW_CLICKED:
                    if (isInReelsView && toggleEngagement && activeReel != null) {
                        handleEngagementClick(event.getSource());
                    }
                    break;
            }
        } catch (Exception e) {}
    }

    private void handleEngagementClick(AccessibilityNodeInfo src) {
        if (src == null) return;
        try {
            String rid = src.getViewIdResourceName();
            if ((IG + ":id/like_button").equals(rid)) {
                CharSequence desc = src.getContentDescription();
                String d = desc != null ? desc.toString().trim().toLowerCase() : "";
                // Toggle: clicking "Like" likes it; clicking "Liked"/"Unlike" unlikes it.
                activeReel.liked = !(src.isSelected() || d.equals("liked") || d.contains("unlike"));
                saveProgressIfChanged();
            } else if ((IG + ":id/comment_button").equals(rid)
                    || (IG + ":id/comment_count").equals(rid)
                    || (IG + ":id/layout_comment_composer_edittext").equals(rid)) {
                activeReel.commented = true;
                saveProgressIfChanged();
            } else if ((IG + ":id/direct_share_button").equals(rid)
                    || (IG + ":id/share_button").equals(rid)) {
                activeReel.shared = true;
                saveProgressIfChanged();
            }
        } finally {
            src.recycle();
        }
    }

    private void checkReelsState() {
        if (powerManager != null && !powerManager.isInteractive()) {
            if (isInReelsView) exitReels();
            return;
        }
        boolean inReels = isReelsPlayer();
        if (inReels && !isInReelsView) enterReels();
        else if (!inReels && isInReelsView) exitReels();
    }

    private boolean isReelsPlayer() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            CharSequence rootPkg = root.getPackageName();
            if (rootPkg != null && !IG.equals(rootPkg.toString())) return false;
            String[] containerIds = {
                IG + ":id/clips_viewer_view_pager", IG + ":id/reel_viewer_root",
                IG + ":id/fragment_clips_viewer_root", IG + ":id/clips_video_container"
            };
            for (String id : containerIds) {
                List<AccessibilityNodeInfo> ns = root.findAccessibilityNodeInfosByViewId(id);
                if (ns != null && !ns.isEmpty()) { recycle(ns); return true; }
            }
            return false;
        } finally { root.recycle(); }
    }

    private void enterReels() {
        isInReelsView = true;
        cancelAllPending();
        pageToReelId.clear();
        activeReel = null;
        currentPageIndex = -1;
        pendingPageIndex = -1;
        resetSavedMarkers();
        startPolling();
        emit("onEnterReels", "{}");
        refreshOverlayCount();

        // Capture the reel already showing on entry (not only reels reached via scrolling).
        long enteredAt = System.currentTimeMillis();
        pendingEntrySettle = () -> {
            pendingEntrySettle = null;
            if (!isInReelsView || activeReel != null || pendingPageIndex >= 0) return;
            commitReelAtPage(-1, enteredAt);
        };
        handler.postDelayed(pendingEntrySettle, ENTRY_SETTLE_MS);
    }

    private void exitReels() {
        isInReelsView = false;
        long now = System.currentTimeMillis();
        // If a fast swipe had just landed on a page right before exiting, commit it first.
        if (pendingPageIndex >= 0 && pendingPageIndex != currentPageIndex) {
            int p = pendingPageIndex;
            long ps = pendingPageStart;
            cancelPendingPageCommit();
            commitReelAtPage(p, ps);
        }
        cancelAllPending();
        stopPolling();
        finalizeActiveReel(now);
        pageToReelId.clear();
        currentPageIndex = -1;
        pendingPageIndex = -1;
        emit("onExitReels", "{}");
        refreshOverlayCount(); // hides the pill since isInReelsView is false
    }

    private void onScroll(AccessibilityEvent event) {
        AccessibilityNodeInfo src = event.getSource();
        if (src == null) return;
        String srcId;
        try {
            srcId = src.getViewIdResourceName();
        } finally {
            src.recycle();
        }

        // Ignore scrolls from comments lists, carousels, or captions.
        if (!REELS_PAGER_ID.equals(srcId)) return;

        int page = event.getFromIndex();
        if (page < 0) return;

        long now = System.currentTimeMillis();

        // Any pager scroll movement (even before snapping to the next index) means the
        // current page may be sliding off screen; cancel its delayed metadata refresh
        // so it can never scrape an incoming page's creator/caption.
        cancelMetadataRefresh();

        // If the entry reel is already active with unknown page index (-1), the first
        // pager event starts from the entry reel's own page index. Bind it so leaving
        // the entry reel records the next page cleanly without recounting the entry reel.
        if (currentPageIndex == -1 && activeReel != null && pendingPageIndex == -1) {
            currentPageIndex = page;
            pageToReelId.put(page, activeReel.id);
            return;
        }

        // Returned to current page (nudge or drag-and-cancel before release).
        if (page == currentPageIndex) {
            cancelPendingPageCommit();
            return;
        }

        // Fast scroll: a brand-new page arrived before the previous pending page committed.
        // Commit the previous pending page immediately so fast swipes are never dropped.
        if (pendingPageIndex >= 0 && pendingPageIndex != page) {
            int prevPending = pendingPageIndex;
            long prevStart = pendingPageStart;
            cancelPendingPageCommit();
            commitReelAtPage(prevPending, prevStart);
        }

        // Queue this page once; repeated settling events for the same page do not push it back.
        if (page != pendingPageIndex) {
            cancelEntrySettle();
            pendingPageIndex = page;
            pendingPageStart = now;
            pendingPageCommit = () -> {
                int targetPage = pendingPageIndex;
                long targetStart = pendingPageStart;
                pendingPageCommit = null;
                pendingPageIndex = -1;
                if (isInReelsView && targetPage >= 0 && targetPage != currentPageIndex) {
                    commitReelAtPage(targetPage, targetStart);
                }
            };
            handler.postDelayed(pendingPageCommit, PAGE_COMMIT_MS);
        }
    }

    private void commitReelAtPage(int page, long startTime) {
        cancelMetadataRefresh();

        // Capture any last-millisecond like state before leaving the old reel.
        if (activeReel != null && toggleEngagement) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                try {
                    Boolean liked = readVisibleLikeState(root);
                    if (liked != null) activeReel.liked = liked;
                } catch (Exception e) {} finally { root.recycle(); }
            }
        }
        finalizeActiveReel(startTime);

        // 1. Scrollback / rewatch within the same Reels pager session -> resume, do not recount.
        if (page >= 0 && pageToReelId.containsKey(page)) {
            long existingId = pageToReelId.get(page);
            TrackedReel existing = reelStates.get(existingId);
            if (existing != null) {
                resumeReel(existing, page, startTime, "scrollback");
                return;
            }
        }

        ScrapedInfo scraped = scrapeCurrentWindow();

        // 2. Skip non-reel interstitial cards (e.g. "Suggested for you" friend grids).
        if (!scraped.hasVideo && scraped.username.isEmpty() && scraped.caption.isEmpty()) {
            currentPageIndex = page;
            activeReel = null;
            // Re-check shortly in case a real reel was still inflating its views.
            if (page >= 0) {
                scheduleLateLoadCheck(page, startTime);
            }
            return;
        }

        // 3. Dedupe across re-entry (returning from comments, profile, share sheet, or app switch).
        // Never collapse a brand-new pager index into a reel already bound to a different page
        // in the current pager session.
        TrackedReel matched = findExistingByContent(scraped.username, scraped.caption);
        if (matched != null && (page < 0 || !pageToReelId.containsValue(matched.id))) {
            if (page >= 0) pageToReelId.put(page, matched.id);
            resumeReel(matched, page, startTime, "revisit");
            return;
        }

        // 4. Genuine new reel -> insert into SQLite.
        String user = toggleMetadata ? scraped.username : "";
        String cap = toggleMetadata ? scraped.caption : "";
        long id = db.insertReel(user, cap);
        TrackedReel reel = new TrackedReel(id);
        reel.username = user;
        reel.caption = cap;
        reel.likeCount = scraped.likeCount;

        reelStates.put(id, reel);
        if (page >= 0) pageToReelId.put(page, id);
        registerFingerprint(reel);

        activeReel = reel;
        currentPageIndex = page;
        activeSegmentStart = startTime;
        resetSavedMarkers();

        Log.d(TAG, "New reel #" + id + " page=" + page + " user=" + reel.username);
        refreshOverlayCount();
        emitScroll(reel, startTime);

        // Refresh metadata shortly after swipe settles in case caption/username bound slightly late.
        scheduleMetadataRefresh(id, page);
    }

    private void resumeReel(TrackedReel existing, int page, long startTime, String reason) {
        activeReel = existing;
        currentPageIndex = page;
        activeSegmentStart = startTime;
        lastSavedLiked = existing.liked;
        lastSavedCommented = existing.commented;
        lastSavedShared = existing.shared;
        lastSavedCompletion = existing.maxCompletion;
        lastSavedWatchMs = existing.accumulatedWatchMs;
        Log.d(TAG, "Resumed reel #" + existing.id + " page=" + page + " user=" + existing.username + " (" + reason + ")");
        refreshOverlayCount();
    }

    private void scheduleLateLoadCheck(int page, long startTime) {
        cancelMetadataRefresh();
        pendingMetadataRefresh = () -> {
            pendingMetadataRefresh = null;
            if (!isInReelsView || currentPageIndex != page || activeReel != null) return;
            ScrapedInfo scraped = scrapeCurrentWindow();
            if (!scraped.hasVideo && scraped.username.isEmpty() && scraped.caption.isEmpty()) {
                Log.d(TAG, "Skipped non-reel card at page=" + page);
                return;
            }
            // Real reel finished inflating; commit it cleanly.
            currentPageIndex = -1;
            commitReelAtPage(page, startTime);
        };
        handler.postDelayed(pendingMetadataRefresh, METADATA_REFRESH_MS);
    }

    private void scheduleMetadataRefresh(long reelId, int page) {
        cancelMetadataRefresh();
        pendingMetadataRefresh = () -> {
            pendingMetadataRefresh = null;
            if (!isInReelsView || activeReel == null || activeReel.id != reelId || currentPageIndex != page) return;

            ScrapedInfo scraped = scrapeCurrentWindow();
            if (!toggleMetadata) return;

            // Only backfill missing fields; never overwrite an already-captured creator/caption.
            String newUser = activeReel.username.isEmpty() ? scraped.username : activeReel.username;
            String newCap = activeReel.caption.isEmpty() ? scraped.caption : activeReel.caption;
            if (newUser.equals(activeReel.username) && newCap.equals(activeReel.caption)) return;

            // If late-loaded metadata reveals this is actually the same reel we just revisited
            // from before an exit, merge watch time into the existing reel and delete the duplicate.
            TrackedReel existing = findExistingByContentExcluding(newUser, newCap, reelId);
            if (existing != null && (page < 0 || !pageToReelId.containsValue(existing.id))) {
                long elapsed = Math.max(0, System.currentTimeMillis() - activeSegmentStart);
                if (toggleWatchTime) existing.accumulatedWatchMs += elapsed;
                existing.maxCompletion = Math.max(existing.maxCompletion, activeReel.maxCompletion);
                existing.liked = existing.liked || activeReel.liked;
                existing.commented = existing.commented || activeReel.commented;
                existing.shared = existing.shared || activeReel.shared;

                db.updateReelWatchData(existing.id, existing.accumulatedWatchMs, existing.maxCompletion);
                db.updateReelEngagement(existing.id, existing.liked, existing.commented, existing.shared);
                db.deleteReel(reelId);
                reelStates.remove(reelId);
                if (page >= 0) pageToReelId.put(page, existing.id);

                activeReel = existing;
                activeSegmentStart = System.currentTimeMillis();
                lastSavedLiked = existing.liked;
                lastSavedCommented = existing.commented;
                lastSavedShared = existing.shared;
                lastSavedCompletion = existing.maxCompletion;
                lastSavedWatchMs = existing.accumulatedWatchMs;
                Log.d(TAG, "Merged late-loaded duplicate #" + reelId + " into #" + existing.id + " user=" + existing.username);
                refreshOverlayCount();
                return;
            }

            activeReel.username = newUser;
            activeReel.caption = newCap;
            if (!scraped.likeCount.isEmpty()) activeReel.likeCount = scraped.likeCount;
            db.updateReelMetadata(reelId, activeReel.username, activeReel.caption);
            registerFingerprint(activeReel);
            emitScroll(activeReel, activeSegmentStart);
        };
        handler.postDelayed(pendingMetadataRefresh, METADATA_REFRESH_MS);
    }

    private void finalizeActiveReel(long endTime) {
        if (activeReel == null) return;
        try {
            long segmentMs = Math.max(0, endTime - activeSegmentStart);
            if (toggleWatchTime) {
                activeReel.accumulatedWatchMs += segmentMs;
            }
            db.updateReelWatchData(activeReel.id, activeReel.accumulatedWatchMs, activeReel.maxCompletion);
            db.updateReelEngagement(activeReel.id, activeReel.liked, activeReel.commented, activeReel.shared);
            lastExitedReel = activeReel;
        } catch (Exception e) {
            Log.e(TAG, "finalizeActiveReel failed", e);
        }
        activeReel = null;
    }

    private void saveProgressIfChanged() {
        if (activeReel == null) return;
        if (activeReel.liked != lastSavedLiked
                || activeReel.commented != lastSavedCommented
                || activeReel.shared != lastSavedShared) {
            db.updateReelEngagement(activeReel.id, activeReel.liked, activeReel.commented, activeReel.shared);
            lastSavedLiked = activeReel.liked;
            lastSavedCommented = activeReel.commented;
            lastSavedShared = activeReel.shared;
        }
        long currentWatch = toggleWatchTime
                ? activeReel.accumulatedWatchMs + Math.max(0, System.currentTimeMillis() - activeSegmentStart)
                : activeReel.accumulatedWatchMs;
        if (activeReel.maxCompletion != lastSavedCompletion || Math.abs(currentWatch - lastSavedWatchMs) >= 2000) {
            db.updateReelWatchData(activeReel.id, currentWatch, activeReel.maxCompletion);
            lastSavedCompletion = activeReel.maxCompletion;
            lastSavedWatchMs = currentWatch;
        }
    }

    private void resetSavedMarkers() {
        lastSavedLiked = false;
        lastSavedCommented = false;
        lastSavedShared = false;
        lastSavedCompletion = -1;
        lastSavedWatchMs = -1;
    }

    private String makeStrictFingerprint(String user, String caption) {
        if (user == null || user.isEmpty() || caption == null || caption.isEmpty()) return "";
        String normCap = caption.replace('\u00A0', ' ').trim().toLowerCase();
        if (normCap.length() > 60) normCap = normCap.substring(0, 60);
        return user.toLowerCase() + "|" + normCap;
    }

    private void registerFingerprint(TrackedReel reel) {
        String fp = makeStrictFingerprint(reel.username, reel.caption);
        if (!fp.isEmpty()) {
            fingerprintToReelId.put(fp, reel.id);
        }
    }

    private TrackedReel findExistingByContent(String user, String caption) {
        return findExistingByContentExcluding(user, caption, -1);
    }

    private TrackedReel findExistingByContentExcluding(String user, String caption, long excludeId) {
        String fp = makeStrictFingerprint(user, caption);
        if (!fp.isEmpty()) {
            Long id = fingerprintToReelId.get(fp);
            if (id != null && id != excludeId) {
                TrackedReel r = reelStates.get(id);
                if (r != null) return r;
            }
        }
        // For captionless reels, only dedupe against the exact reel we just exited
        // (returning from app switch, lock screen, or profile without scrolling).
        if (lastExitedReel != null
                && lastExitedReel.id != excludeId
                && user != null && !user.isEmpty()
                && user.equalsIgnoreCase(lastExitedReel.username)
                && (caption == null || caption.isEmpty())
                && (lastExitedReel.caption == null || lastExitedReel.caption.isEmpty())) {
            return lastExitedReel;
        }
        return null;
    }

    private boolean sameVisibleReel(TrackedReel reel, ScrapedInfo visible) {
        if (reel == null || visible == null) return false;
        if (!reel.username.isEmpty() && !visible.username.isEmpty()) {
            if (!reel.username.equalsIgnoreCase(visible.username)) return false;
            if (!reel.caption.isEmpty() && !visible.caption.isEmpty()) {
                return reel.caption.equals(visible.caption);
            }
            return true;
        }
        return false;
    }

    private void refreshOverlayCount() {
        try { updateOverlayText("Reels Today: " + db.getTodayReelCount()); } catch (Exception e) {}
    }

    private void cancelPendingPageCommit() {
        if (pendingPageCommit != null) { handler.removeCallbacks(pendingPageCommit); pendingPageCommit = null; }
        pendingPageIndex = -1;
    }

    private void cancelEntrySettle() {
        if (pendingEntrySettle != null) { handler.removeCallbacks(pendingEntrySettle); pendingEntrySettle = null; }
    }

    private void cancelMetadataRefresh() {
        if (pendingMetadataRefresh != null) { handler.removeCallbacks(pendingMetadataRefresh); pendingMetadataRefresh = null; }
    }

    private void cancelAllPending() {
        cancelPendingPageCommit();
        cancelEntrySettle();
        cancelMetadataRefresh();
    }

    private void startPolling() {
        stopPolling();
        progressPoller = new Runnable() {
            @Override public void run() {
                if (isInReelsView) {
                    if (powerManager != null && !powerManager.isInteractive()) {
                        exitReels();
                        return;
                    }
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    if (root != null) {
                        try {
                            CharSequence rootPkg = root.getPackageName();
                            if (rootPkg != null && !IG.equals(rootPkg.toString())) {
                                exitReels();
                                return;
                            }
                            if (activeReel != null) {
                                if (toggleCompletion) {
                                    double c = getCompletion(root);
                                    if (c > activeReel.maxCompletion) activeReel.maxCompletion = c;
                                }
                                if (toggleEngagement) {
                                    Boolean liked = readVisibleLikeState(root);
                                    if (liked != null) activeReel.liked = liked;
                                }
                                saveProgressIfChanged();

                                JSONObject d = new JSONObject();
                                d.put("completionPercent", activeReel.maxCompletion);
                                d.put("liked", activeReel.liked);
                                d.put("commented", activeReel.commented);
                                d.put("shared", activeReel.shared);
                                emit("onProgressUpdate", d.toString());
                            }
                        } catch (Exception e) {}
                        finally { root.recycle(); }
                    }
                }
                if (isInReelsView) {
                    handler.postDelayed(this, 500);
                }
            }
        };
        handler.postDelayed(progressPoller, 500);
    }

    private void stopPolling() {
        if (progressPoller != null) { handler.removeCallbacks(progressPoller); progressPoller = null; }
    }

    private int getScreenWidth() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return dm != null && dm.widthPixels > 0 ? dm.widthPixels : 1080;
    }

    private int getScreenHeight() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return dm != null && dm.heightPixels > 0 ? dm.heightPixels : 2520;
    }

    private boolean isOnScreen(AccessibilityNodeInfo node, int screenW, int screenH) {
        return visibleOverlapHeight(node, screenW, screenH) > 0;
    }

    private int visibleOverlapHeight(AccessibilityNodeInfo node, int screenW, int screenH) {
        if (node == null || !node.isVisibleToUser()) return 0;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.width() <= 0 || r.height() <= 0 || r.right <= 0 || r.left >= screenW) return 0;
        int top = Math.max(0, r.top);
        int bottom = Math.min(screenH, r.bottom);
        return Math.max(0, bottom - top);
    }

    /** Picks the node most firmly inside the main screen area (ignoring pages sliding off the top/bottom edge). */
    private AccessibilityNodeInfo pickMostVisible(List<AccessibilityNodeInfo> nodes, int screenW, int screenH) {
        if (nodes == null || nodes.isEmpty()) return null;
        AccessibilityNodeInfo best = null;
        int bestScore = 0;
        int centerY = screenH / 2;
        for (AccessibilityNodeInfo n : nodes) {
            int overlap = visibleOverlapHeight(n, screenW, screenH);
            if (overlap <= 0) continue;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            int nodeCenterY = (r.top + r.bottom) / 2;
            // Prefer nodes with larger visible overlap and closer to the screen body (not clipped at edges).
            int distPenalty = Math.abs(nodeCenterY - centerY) / 4;
            int score = overlap * 10 - distPenalty;
            if (best == null || score > bestScore) {
                best = n;
                bestScore = score;
            }
        }
        return best;
    }

    /** Returns true/false if the Reel's like button is visible on screen, or null if covered (e.g. comments sheet). */
    private Boolean readVisibleLikeState(AccessibilityNodeInfo root) {
        if (root == null) return null;
        int w = getScreenWidth(), h = getScreenHeight();
        List<AccessibilityNodeInfo> ns = root.findAccessibilityNodeInfosByViewId(IG + ":id/like_button");
        if (ns == null || ns.isEmpty()) return null;
        Boolean result = null;
        for (AccessibilityNodeInfo n : ns) {
            if (!isOnScreen(n, w, h)) continue;
            CharSequence desc = n.getContentDescription();
            String d = desc != null ? desc.toString().trim().toLowerCase() : "";
            result = n.isSelected() || d.equals("liked") || d.contains("unlike");
            break;
        }
        recycle(ns);
        return result;
    }

    private double getCompletion(AccessibilityNodeInfo root) {
        if (root == null) return 0;
        String[] ids = { IG + ":id/clips_progress_bar", IG + ":id/reel_viewer_progress", IG + ":id/scrubber" };
        for (String id : ids) {
            List<AccessibilityNodeInfo> ns = root.findAccessibilityNodeInfosByViewId(id);
            if (ns != null && !ns.isEmpty()) {
                for (AccessibilityNodeInfo n : ns) {
                    AccessibilityNodeInfo.RangeInfo ri = n.getRangeInfo();
                    if (ri != null && ri.getMax() > 0) {
                        double p = (ri.getCurrent() / ri.getMax()) * 100;
                        recycle(ns);
                        return p;
                    }
                }
                recycle(ns);
            }
        }
        return 0;
    }

    private ScrapedInfo scrapeCurrentWindow() {
        ScrapedInfo info = new ScrapedInfo();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return info;
        try {
            int w = getScreenWidth(), h = getScreenHeight();

            List<AccessibilityNodeInfo> vids = root.findAccessibilityNodeInfosByViewId(IG + ":id/clips_video_container");
            if (vids != null && !vids.isEmpty()) {
                for (AccessibilityNodeInfo n : vids) {
                    if (isOnScreen(n, w, h)) { info.hasVideo = true; break; }
                }
                recycle(vids);
            }

            String[] lIds = { IG + ":id/like_count", IG + ":id/row_feed_textview_likes", IG + ":id/clips_viewer_like_count" };
            for (String id : lIds) {
                List<AccessibilityNodeInfo> ns = root.findAccessibilityNodeInfosByViewId(id);
                if (ns != null && !ns.isEmpty()) {
                    for (AccessibilityNodeInfo n : ns) {
                        if (!isOnScreen(n, w, h)) continue;
                        String t = getTextOrDesc(n).trim();
                        if (!t.isEmpty()) {
                            Matcher m = LIKE_NUM_PATTERN.matcher(t);
                            if (m.find()) { info.likeCount = m.group(1).replace(" ", ""); break; }
                        }
                    }
                    recycle(ns);
                    if (!info.likeCount.isEmpty()) break;
                }
            }

            String[] uIds = { IG + ":id/clips_author_username", IG + ":id/reel_viewer_username", IG + ":id/clips_username", IG + ":id/username_text_view" };
            for (String id : uIds) {
                List<AccessibilityNodeInfo> ns = root.findAccessibilityNodeInfosByViewId(id);
                if (ns != null && !ns.isEmpty()) {
                    AccessibilityNodeInfo best = pickMostVisible(ns, w, h);
                    if (best != null) {
                        String t = cleanUsername(getTextOrDesc(best));
                        if (!t.isEmpty() && !t.contains(" ")) info.username = t;
                    }
                    recycle(ns);
                    if (!info.username.isEmpty()) break;
                }
            }

            if (info.username.isEmpty()) {
                List<AccessibilityNodeInfo> ns = root.findAccessibilityNodeInfosByViewId(IG + ":id/clips_author_profile_pic");
                if (ns != null && !ns.isEmpty()) {
                    AccessibilityNodeInfo best = pickMostVisible(ns, w, h);
                    if (best != null) {
                        String d = getTextOrDesc(best);
                        if (d.startsWith("Profile picture of ")) {
                            String t = cleanUsername(d.substring("Profile picture of ".length()));
                            if (!t.isEmpty() && !t.contains(" ")) info.username = t;
                        }
                    }
                    recycle(ns);
                }
            }

            String[] cIds = { IG + ":id/clips_caption_component", IG + ":id/clips_caption", IG + ":id/reel_viewer_caption", IG + ":id/clips_caption_text" };
            for (String id : cIds) {
                List<AccessibilityNodeInfo> ns = root.findAccessibilityNodeInfosByViewId(id);
                if (ns != null && !ns.isEmpty()) {
                    AccessibilityNodeInfo best = pickMostVisible(ns, w, h);
                    if (best != null) {
                        String t = deepText(best);
                        if (!t.isEmpty()) info.caption = t.trim();
                    }
                    recycle(ns);
                    if (!info.caption.isEmpty()) break;
                }
            }

            extractFromContentDescriptions(root, info, 0);
        } catch (Exception e) {
        } finally {
            root.recycle();
        }
        return info;
    }

    private void extractFromContentDescriptions(AccessibilityNodeInfo node, ScrapedInfo info, int depth) {
        if (node == null || depth > 12) return;
        if (!info.username.isEmpty() && !info.caption.isEmpty()) return;

        CharSequence descCs = node.getContentDescription();
        if (descCs != null) {
            String desc = descCs.toString().trim();
            if (info.caption.isEmpty() && desc.contains(" said ")) {
                int idx = desc.indexOf(" said ");
                String user = cleanUsername(desc.substring(0, idx));
                String caption = desc.substring(idx + 6).trim();
                if (!caption.isEmpty() && caption.length() > 3) {
                    info.caption = caption;
                    if (info.username.isEmpty() && !user.isEmpty() && !user.contains(" ")) {
                        info.username = user;
                    }
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) { extractFromContentDescriptions(child, info, depth + 1); child.recycle(); }
        }
    }

    private String cleanUsername(String raw) {
        if (raw == null) return "";
        return raw.replace('\u00A0', ' ').replace("@", "").trim();
    }

    private String getTextOrDesc(AccessibilityNodeInfo n) {
        if (n == null) return "";
        if (n.getText() != null && n.getText().length() > 0) return n.getText().toString();
        if (n.getContentDescription() != null && n.getContentDescription().length() > 0) return n.getContentDescription().toString();
        return "";
    }

    private boolean isUiLabel(String text) {
        String lower = text.toLowerCase().trim();
        String[] uiStrings = {
            "turn on sound", "tap to unmute", "follow", "following",
            "share", "like", "comment", "send", "more", "audio",
            "original audio", "reel", "sponsored", "remix", "use audio",
            "view translation", "see translation", "translate", "save", "likes",
            "see more", "more..."
        };
        for (String ui : uiStrings) { if (lower.equals(ui)) return true; }
        return lower.length() < 3;
    }

    private String deepText(AccessibilityNodeInfo node) {
        if (node == null) return "";
        StringBuilder sb = new StringBuilder();
        collectText(node, sb, 0);
        return sb.toString().trim();
    }

    private void collectText(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null || depth > 6) return;
        String t = getTextOrDesc(node).replace('\u00A0', ' ').trim();
        if (!t.isEmpty() && !isUiLabel(t)) { sb.append(t).append(" "); }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c != null) { collectText(c, sb, depth + 1); c.recycle(); }
        }
    }

    private void emitScroll(TrackedReel reel, long ts) {
        try {
            JSONObject d = new JSONObject();
            d.put("username", reel.username);
            d.put("caption", reel.caption);
            d.put("likeCount", reel.likeCount);
            d.put("timestamp", ts);
            d.put("reelId", reel.id);
            emit("onReelScrolled", d.toString());
        } catch (JSONException e) {}
    }

    private void recycle(List<AccessibilityNodeInfo> ns) { if (ns != null) for (AccessibilityNodeInfo n : ns) if (n != null) n.recycle(); }
    private void emit(String name, String json) { ReelsTrackerModule m = ReelsTrackerModule.getInstance(); if (m != null) m.sendEvent(name, json); }

    private void loadToggles() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        toggleMetadata = p.getBoolean("toggle_metadata", true);
        toggleWatchTime = p.getBoolean("toggle_watchtime", true);
        toggleCompletion = p.getBoolean("toggle_completion", true);
        toggleEngagement = p.getBoolean("toggle_engagement", true);
        toggleOcr = p.getBoolean("toggle_ocr", true);
    }

    public void saveToggle(String key, boolean val) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(key, val).apply();
        switch (key) {
            case "toggle_metadata": toggleMetadata = val; break;
            case "toggle_watchtime": toggleWatchTime = val; break;
            case "toggle_completion": toggleCompletion = val; break;
            case "toggle_engagement": toggleEngagement = val; break;
            case "toggle_ocr": toggleOcr = val; break;
        }
    }

    public boolean getToggle(String key) {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(key, true);
    }

    public String getServiceStatus() {
        try {
            JSONObject s = new JSONObject();
            s.put("isRunning", true);
            s.put("isInReelsView", isInReelsView);
            return s.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }
}
