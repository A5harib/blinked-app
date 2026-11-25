import { useEffect, useState, useCallback } from 'react';
import { AppState, NativeModules, NativeEventEmitter, Platform } from 'react-native';
import {
  loadReels,
  updateReel,
  clearAllReels,
  calculateStatsFromSql,
} from '../utils/reelsStore';

const { ReelsTrackerModule } = NativeModules;
const eventEmitter = Platform.OS === 'android' && ReelsTrackerModule
  ? new NativeEventEmitter(ReelsTrackerModule)
  : null;

// Space to add Groq API key:
// You can define it in process.env.GROQ_API_KEY or assign it here directly.
declare const process: any;
const GROQ_API_KEY = (typeof process !== 'undefined' && process.env ? process.env.GROQ_API_KEY : "") || "YOUR_GROQ_API_KEY_HERE";

export interface ReelData {
  id: number;
  username: string;
  caption: string;
  watchTimeMs: number;
  completionPercent: number;
  liked: boolean;
  commented: boolean;
  shared: boolean;
  thumbnailPath?: string;
  ocrText: string;
  timestamp: number;
}

export interface Stats {
  totalReels: number;
  totalWatchTimeMs: number;
  avgWatchTimeMs: number;
  avgCompletionPercent: number;
  totalLikes: number;
  totalComments: number;
  todayReels: number;
  todayWatchTimeMs: number;
  hourlyActivity?: number[];
  topCreators?: Array<{ username: string; watchTimeMs: number; count: number }>;
  topKeywords?: Array<{ text: string; value: number }>;
}

export interface ServiceStatus {
  isRunning: boolean;
  isInReelsView: boolean;
  currentReelId: number;
  reelCounter: number;
  currentUsername: string;
}

export function useReelsTracker() {
  const [isEnabled, setIsEnabled] = useState(false);
  const [status, setStatus] = useState<ServiceStatus>({
    isRunning: false,
    isInReelsView: false,
    currentReelId: -1,
    reelCounter: 0,
    currentUsername: '',
  });
  
  const [stats, setStats] = useState<Stats>({
    totalReels: 0,
    totalWatchTimeMs: 0,
    avgWatchTimeMs: 0,
    avgCompletionPercent: 0,
    totalLikes: 0,
    totalComments: 0,
    todayReels: 0,
    todayWatchTimeMs: 0,
    hourlyActivity: Array(24).fill(0),
    topCreators: [],
    topKeywords: [],
  });
  
  const [recentReels, setRecentReels] = useState<ReelData[]>([]);
  const [liveEvent, setLiveEvent] = useState<{ name: string; data: any } | null>(null);

  const checkService = useCallback(async () => {
    if (!ReelsTrackerModule) return;
    try {
      const enabled = await ReelsTrackerModule.isServiceEnabled();
      setIsEnabled(enabled);
      const raw = await ReelsTrackerModule.getServiceStatus();
      const s = JSON.parse(raw);
      setStatus(prev => ({
        ...prev,
        isRunning: s.isRunning,
        isInReelsView: s.isInReelsView,
      }));
    } catch (e) {
      console.warn('checkService error', e);
    }
  }, []);

  const refreshStatsAndReels = useCallback(async () => {
    try {
      const reels = await loadReels();
      setRecentReels(reels);
      const computed = await calculateStatsFromSql();
      setStats(computed);
      
      // Sync overlay count with today's count calculated in JS
      if (ReelsTrackerModule) {
        ReelsTrackerModule.updateOverlay(`Reels Today: ${computed.todayReels}`);
      }
    } catch (e) {
      console.warn('refreshStatsAndReels error', e);
    }
  }, []);

  const openSettings = useCallback(() => {
    ReelsTrackerModule?.openAccessibilitySettings();
  }, []);

  const setToggle = useCallback(async (key: string, value: boolean) => {
    if (!ReelsTrackerModule) return;
    await ReelsTrackerModule.setToggle(key, value);
  }, []);

  const getToggle = useCallback(async (key: string): Promise<boolean> => {
    if (!ReelsTrackerModule) return true;
    try {
      return await ReelsTrackerModule.getToggle(key);
    } catch {
      return true;
    }
  }, []);

  const clearHistory = useCallback(async () => {
    try {
      await clearAllReels();
      setRecentReels([]);
      setStats({
        totalReels: 0,
        totalWatchTimeMs: 0,
        avgWatchTimeMs: 0,
        avgCompletionPercent: 0,
        totalLikes: 0,
        totalComments: 0,
        todayReels: 0,
        todayWatchTimeMs: 0,
        hourlyActivity: Array(24).fill(0),
        topCreators: [],
        topKeywords: [],
      });
      if (ReelsTrackerModule) {
        ReelsTrackerModule.updateOverlay("Reels Today: 0");
      }
    } catch (e) {
      console.warn('clearHistory error', e);
    }
  }, []);

  // Groq OCR API integration with Fallback mechanism
  const performGroqOcr = async (base64Image: string): Promise<{ username: string; caption: string; ocrText: string }> => {
    if (!GROQ_API_KEY || GROQ_API_KEY === "YOUR_GROQ_API_KEY_HERE") {
      console.warn("Groq API key is not configured. Please add the key in env or useReelsTracker.ts.");
      return { username: "", caption: "", ocrText: "Groq key missing" };
    }

    const payload = {
      model: "meta-llama/llama-4-scout-17b-16e-instruct",
      messages: [
        {
          role: "user",
          content: [
            {
              type: "text",
              text: "Extract the Instagram username and caption text visible in this Reel. Return JSON ONLY in this format: { \"username\": \"...\", \"caption\": \"...\" } without markdown packaging."
            },
            {
              type: "image_url",
              image_url: {
                url: `data:image/jpeg;base64,${base64Image}`
              }
            }
          ]
        }
      ],
      response_format: { type: "json_object" }
    };

    // Primary request
    try {
      const response = await fetch("https://api.groq.com/openai/v1/chat/completions", {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${GROQ_API_KEY}`,
          "Content-Type": "application/json"
        },
        body: JSON.stringify(payload)
      });

      if (!response.ok) {
        throw new Error(`Primary Groq model call failed: ${response.status} ${response.statusText}`);
      }

      const resJson = await response.json();
      const content = resJson.choices?.[0]?.message?.content || "";
      const parsed = JSON.parse(content);
      return {
        username: parsed.username || "",
        caption: parsed.caption || "",
        ocrText: content
      };
    } catch (err) {
      console.warn("Groq primary model failed, attempting Qwen fallback...", err);
      
      // Fallback model request
      const fallbackPayload = {
        ...payload,
        model: "qwen/qwen3.6-27b"
      };

      try {
        const response = await fetch("https://api.groq.com/openai/v1/chat/completions", {
          method: "POST",
          headers: {
            "Authorization": `Bearer ${GROQ_API_KEY}`,
            "Content-Type": "application/json"
          },
          body: JSON.stringify(fallbackPayload)
        });

        if (!response.ok) {
          throw new Error(`Fallback Qwen model call failed: ${response.status} ${response.statusText}`);
        }

        const resJson = await response.json();
        const content = resJson.choices?.[0]?.message?.content || "";
        const parsed = JSON.parse(content);
        return {
          username: parsed.username || "",
          caption: parsed.caption || "",
          ocrText: content
        };
      } catch (fallbackErr) {
        console.error("OCR Pipeline completely failed:", fallbackErr);
        return { username: "", caption: "", ocrText: `OCR processing error: ${fallbackErr}` };
      }
    }
  };

  useEffect(() => {
    checkService();
    refreshStatsAndReels();

    if (!eventEmitter) return;

    // The accessibility service records reels into SQLite on its own, because JS
    // timers are paused while ScrollSoul sits in the background. These listeners
    // only refresh the UI and run optional OCR for the reel the service saved.
    const subs = [
      eventEmitter.addListener('onReelScrolled', async (e) => {
        const data = JSON.parse(e.data); // username, caption, likeCount, timestamp, reelId
        setLiveEvent({ name: 'scroll', data });
        setStatus(prev => ({
          ...prev,
          currentReelId: data.reelId,
          currentUsername: data.username || 'Scanning...',
        }));
        refreshStatsAndReels();

        const ocrEnabled = await ReelsTrackerModule?.getToggle('toggle_ocr');
        if (ocrEnabled && data.reelId > 0 && GROQ_API_KEY !== 'YOUR_GROQ_API_KEY_HERE') {
          try {
            const base64 = await ReelsTrackerModule.takeScreenshot();
            const ocr = await performGroqOcr(base64);
            await updateReel(data.reelId, {
              username: ocr.username || data.username || 'unknown',
              caption: ocr.caption || data.caption || '',
              ocrText: ocr.ocrText,
            });
            refreshStatsAndReels();
          } catch (err) {
            console.warn('OCR error:', err);
          }
        }
      }),

      eventEmitter.addListener('onProgressUpdate', (e) => {
        setLiveEvent({ name: 'progress', data: JSON.parse(e.data) });
      }),

      eventEmitter.addListener('onEnterReels', () => {
        setLiveEvent({ name: 'enter', data: {} });
        checkService();
        refreshStatsAndReels();
      }),

      eventEmitter.addListener('onExitReels', () => {
        setLiveEvent({ name: 'exit', data: {} });
        checkService();
        refreshStatsAndReels();
      }),
    ];

    // Catch up on reels recorded while the app was in the background.
    const appStateSub = AppState.addEventListener('change', state => {
      if (state === 'active') {
        checkService();
        refreshStatsAndReels();
      }
    });

    const interval = setInterval(() => {
      checkService();
      refreshStatsAndReels();
    }, 15000);

    return () => {
      subs.forEach(s => s.remove());
      appStateSub.remove();
      clearInterval(interval);
    };
  }, [checkService, refreshStatsAndReels]);

  return {
    isEnabled,
    status,
    stats,
    recentReels,
    liveEvent,
    openSettings,
    setToggle,
    getToggle,
    refreshStats: refreshStatsAndReels,
    refreshReels: refreshStatsAndReels,
    checkService,
    clearHistory,
  };
}
