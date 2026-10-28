// ==UserScript==
// @name         ntfy Sync: gelesen-Status an Android melden
// @namespace    com.wagnermartin.ntfysync
// @version      1.0.0
// @description  Messages read in the ntfy web app are also marked as read in the ntfy Sync Android app (and on other devices), using ntfy's built-in "clear" feature. No server changes needed.
// @match        https://ntfy.1337733.xyz/*
// @run-at       document-start
// @grant        none
// ==/UserScript==

/*
 * How it works:
 * - The ntfy web app stores "read" only locally in IndexedDB (field "new": 1 = unread, 0 = read).
 * - Every 2 seconds (only while the tab is visible) this script looks at the unread messages.
 *   A message that was unread before and is now stored with new = 0 was read by you.
 * - For those, it sends PUT /<topic>/<sequence_id>/clear. The server turns this into a
 *   message_clear event, which the Android app uses to mark the message as read and remove the
 *   notification.
 * - Clears that arrive from other devices (WebSocket) are remembered and not sent back, so there
 *   is no ping-pong.
 * Requires ntfy server v2.17.0 or newer, and write access to the topic.
 */
(() => {
  "use strict";

  const TICK_MILLIS = 2000;
  const SEND_INTERVAL_MILLIS = 200; // Be gentle with the server's request rate limit
  const MAX_PER_TICK = 50;
  const LOG_PREFIX = "[ntfy-read-sync]";

  const receivedClears = new Set(); // "topic|sequenceId" of clears sent by other devices
  let previousUnread = null; // Map id -> notification; null until the first snapshot
  let busy = false;

  const key = (topic, sequenceId) => `${topic}|${sequenceId}`;
  const topicOf = (subscriptionId) => subscriptionId.substring(subscriptionId.lastIndexOf("/") + 1);
  const baseUrlOf = (subscriptionId) => subscriptionId.substring(0, subscriptionId.lastIndexOf("/"));
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

  // 1. Remember incoming message_clear events, so we don't echo them back to the server
  const NativeWebSocket = window.WebSocket;
  const WrappedWebSocket = function (...args) {
    const ws = new NativeWebSocket(...args);
    ws.addEventListener("message", (event) => {
      try {
        const message = JSON.parse(event.data);
        if (message.event === "message_clear" && message.topic && message.sequence_id) {
          receivedClears.add(key(message.topic, message.sequence_id));
        }
      } catch (_) {
        // Not JSON, not ours
      }
    });
    return ws;
  };
  WrappedWebSocket.prototype = NativeWebSocket.prototype;
  ["CONNECTING", "OPEN", "CLOSING", "CLOSED"].forEach((name) => (WrappedWebSocket[name] = NativeWebSocket[name]));
  window.WebSocket = WrappedWebSocket;

  // 2. Read the web app's IndexedDB (same database name logic as web/src/app/db.js)
  const databaseName = () => {
    const username = localStorage.getItem("user");
    return username ? `ntfy-${username}` : "ntfy";
  };

  const request = (req) =>
    new Promise((resolve, reject) => {
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });

  const openDatabase = () =>
    new Promise((resolve, reject) => {
      const req = indexedDB.open(databaseName());
      req.onupgradeneeded = () => req.transaction.abort(); // Database doesn't exist yet; never create it
      req.onsuccess = () => {
        const db = req.result;
        db.onversionchange = () => db.close(); // Never block the web app's own upgrades
        resolve(db);
      };
      req.onerror = () => reject(req.error);
    });

  // Returns the unread messages now, and those of previousIds that are no longer unread because they were read
  const snapshot = async (previousIds) => {
    const db = await openDatabase();
    try {
      if (!db.objectStoreNames.contains("notifications")) {
        return { unread: new Map(), read: [], users: [] };
      }
      const tx = db.transaction(["notifications", "users"], "readonly");
      const notifications = tx.objectStore("notifications");
      const unreadList = await request(notifications.index("new").getAll(1));
      const unreadIds = new Set(unreadList.map((n) => n.id));
      const vanishedIds = previousIds.filter((id) => !unreadIds.has(id));
      const vanished = await Promise.all(vanishedIds.map((id) => request(notifications.get(id))));
      const users = await request(tx.objectStore("users").getAll());
      return {
        unread: new Map(unreadList.map((n) => [n.id, n])),
        read: vanished.filter((n) => n && n.new === 0), // Deleted or replaced messages are not "read"
        users,
      };
    } finally {
      db.close();
    }
  };

  // 3. Tell the server; it turns this into a message_clear event for all other clients
  const authHeader = (baseUrl, users) => {
    const token = localStorage.getItem("token");
    if (baseUrl === location.origin && token) {
      return `Bearer ${token}`;
    }
    const user = users.find((u) => u.baseUrl === baseUrl);
    if (user && user.token) {
      return `Bearer ${user.token}`;
    }
    if (user && user.username) {
      return `Basic ${btoa(`${user.username}:${user.password ?? ""}`)}`;
    }
    return null;
  };

  const sendClear = async (notification, users) => {
    const sequenceId = notification.sequenceId || notification.id;
    const baseUrl = baseUrlOf(notification.subscriptionId);
    const url = `${notification.subscriptionId}/${encodeURIComponent(sequenceId)}/clear`;
    const headers = {};
    const auth = authHeader(baseUrl, users);
    if (auth) headers.Authorization = auth;
    try {
      const response = await fetch(url, { method: "PUT", headers });
      if (!response.ok) {
        console.warn(LOG_PREFIX, `Clearing ${sequenceId} failed: HTTP ${response.status}`);
      }
    } catch (e) {
      console.warn(LOG_PREFIX, `Clearing ${sequenceId} failed`, e);
    }
  };

  const tick = async () => {
    if (busy) return;
    busy = true;
    try {
      const { unread, read, users } = await snapshot(previousUnread ? [...previousUnread.keys()] : []);
      const isFirstSnapshot = previousUnread === null;
      previousUnread = unread;
      if (isFirstSnapshot) return; // Baseline only; never send for history

      const seen = new Set();
      const toSend = [];
      for (const notification of read) {
        const topic = topicOf(notification.subscriptionId);
        const k = key(topic, notification.sequenceId || notification.id);
        if (seen.has(k)) continue;
        seen.add(k);
        if (receivedClears.delete(k)) continue; // Read on another device; don't echo
        toSend.push(notification);
      }
      for (const notification of toSend.slice(0, MAX_PER_TICK)) {
        await sendClear(notification, users);
        await sleep(SEND_INTERVAL_MILLIS);
      }
      if (toSend.length > 0) console.log(LOG_PREFIX, `Marked ${Math.min(toSend.length, MAX_PER_TICK)} message(s) as read on other devices`);
    } catch (e) {
      console.warn(LOG_PREFIX, "Check failed", e);
    } finally {
      busy = false;
    }
  };

  setInterval(() => {
    if (!document.hidden) tick();
  }, TICK_MILLIS);
  document.addEventListener("visibilitychange", () => tick()); // Catch reads right before switching tabs
})();
