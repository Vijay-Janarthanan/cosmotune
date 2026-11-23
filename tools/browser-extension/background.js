// Open in Cosmotune: the toolbar button opens the YouTube video of the tab in Cosmotune's Video mode, and "Open in Cosmotune"
// on a YouTube link opens that one. It only builds a cosmotune://video link; the desktop app must open cosmotune:// links
// (Watch, Plugins, "Set as handler" on Windows and Linux; the macOS app does it by itself). Nothing is sent anywhere else.
const api = typeof browser !== "undefined" ? browser : chrome;
const PATTERNS = ["*://*.youtube.com/*", "*://youtu.be/*", "*://*.youtube-nocookie.com/*"];

function isVideo(url) {
  return /^https?:\/\/((www|m)\.)?(youtube\.com\/(watch\?|shorts\/|live\/|embed\/)|youtu\.be\/)/i.test(url || "");
}

function open(tabId, url) {
  if (!isVideo(url)) return;
  api.tabs.update(tabId, { url: "cosmotune://video?url=" + encodeURIComponent(url) });
}

api.runtime.onInstalled.addListener(() => {
  api.contextMenus.create({ id: "cosmotune-link", title: "Open in Cosmotune", contexts: ["link"], targetUrlPatterns: PATTERNS });
});

api.contextMenus.onClicked.addListener((info, tab) => {
  if (info.menuItemId === "cosmotune-link" && tab) open(tab.id, info.linkUrl);
});

api.action.onClicked.addListener((tab) => open(tab.id, tab.url));
