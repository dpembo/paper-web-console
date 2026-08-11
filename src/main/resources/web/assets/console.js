import { fetchJson, formatRelativeTime, showToast } from "/assets/shared.js";

const appShell = document.querySelector(".app-shell");
const output = document.getElementById("consoleOutput");
const commandForm = document.getElementById("commandForm");
const commandInput = document.getElementById("commandInput");
const inlineStatus = document.getElementById("inlineStatus");
const connectionBadge = document.getElementById("connectionBadge");
const scrollBadge = document.getElementById("scrollBadge");
const endpointValue = document.getElementById("endpointValue");
const serverValue = document.getElementById("serverValue");
const sessionValue = document.getElementById("sessionValue");
const bufferValue = document.getElementById("bufferValue");
const reconnectButton = document.getElementById("reconnectButton");
const clearButton = document.getElementById("clearButton");
const wrapButton = document.getElementById("wrapButton");
const logoutButton = document.getElementById("logoutButton");
const panelToggle = document.getElementById("panelToggle");
const panelBackdrop = document.getElementById("panelBackdrop");
const railToggle = document.getElementById("railToggle");

let socket = null;
let reconnectTimer = null;
let activeStatus = null;
let autoScroll = true;
let commandHistory = [];
let historyCursor = -1;

try {
    if (localStorage.getItem("railCollapsed") === "true") {
        appShell.dataset.railCollapsed = "true";
        railToggle.textContent = "<";
        railToggle.setAttribute("aria-expanded", "false");
        railToggle.setAttribute("aria-label", "Expand status panel");
    }
} catch { /* storage unavailable */ }

boot();

async function boot() {
    try {
        activeStatus = await fetchJson("/api/status", { method: "GET" });
        if (!activeStatus.configured) {
            window.location.replace("/setup");
            return;
        }
        if (!activeStatus.authenticated) {
            window.location.replace("/login");
            return;
        }

        hydrateStatus(activeStatus);
        output.classList.toggle("wrap-enabled", activeStatus.ui.defaultWrapMode);
        inlineStatus.textContent = "Connecting to live console stream…";
        connectSocket();
    } catch (error) {
        inlineStatus.textContent = error.message;
        showToast(error.message, "error");
    }
}

function hydrateStatus(status) {
    endpointValue.textContent = `${status.bindAddress}:${status.port}`;
    serverValue.textContent = `${status.serverName} ${status.minecraftVersion}`;
    sessionValue.textContent = formatRelativeTime(status.sessionExpiresAtEpochMillis);
    bufferValue.textContent = `${status.ui.maxBufferedLines} lines`;
}

function isCompactLayout() {
    return window.matchMedia("(max-width: 1040px)").matches;
}

function setPanelOpen(isOpen) {
    appShell.dataset.panelOpen = String(isOpen);
    panelToggle.setAttribute("aria-expanded", String(isOpen));
    panelBackdrop.classList.toggle("hidden", !isOpen);
}

function closePanel() {
    if (isCompactLayout()) {
        setPanelOpen(false);
    }
}

function setRailCollapsed(isCollapsed) {
    appShell.dataset.railCollapsed = String(isCollapsed);
    railToggle.textContent = isCollapsed ? "<" : ">";
    railToggle.setAttribute("aria-label", isCollapsed ? "Expand status panel" : "Collapse status panel");
    railToggle.setAttribute("aria-expanded", String(!isCollapsed));
    try { localStorage.setItem("railCollapsed", String(isCollapsed)); } catch { /* storage unavailable */ }
}

function connectSocket() {
    if (socket && socket.readyState === WebSocket.OPEN) {
        return;
    }

    setConnectionState("Connecting", "badge-live");
    const protocol = window.location.protocol === "https:" ? "wss" : "ws";
    socket = new WebSocket(`${protocol}://${window.location.host}/ws/console`);

    socket.addEventListener("open", () => {
        inlineStatus.textContent = "Connected. Waiting for live output…";
        setConnectionState("Live", "badge-live");
    });

    socket.addEventListener("message", event => {
        const message = JSON.parse(event.data);
        handleSocketMessage(message);
    });

    socket.addEventListener("close", () => {
        setConnectionState("Offline", "badge-muted");
        inlineStatus.textContent = "Disconnected. Reconnecting shortly…";
        queueReconnect();
    });

    socket.addEventListener("error", () => {
        setConnectionState("Error", "badge-muted");
        inlineStatus.textContent = "Socket error. Waiting to reconnect…";
    });
}

function queueReconnect() {
    window.clearTimeout(reconnectTimer);
    reconnectTimer = window.setTimeout(() => {
        connectSocket();
    }, 1500);
}

function handleSocketMessage(message) {
    switch (message.type) {
        case "history":
            output.replaceChildren();
            message.payload.forEach(renderLine);
            stickToBottom(true);
            break;
        case "log":
            renderLine(message.payload);
            if (autoScroll) {
                stickToBottom(false);
            } else {
                scrollBadge.classList.remove("hidden");
            }
            break;
        case "status":
            inlineStatus.textContent = message.payload.state === "alive" ? "Connection healthy." : "Live stream connected.";
            break;
        case "error":
            inlineStatus.textContent = message.payload.message;
            showToast(message.payload.message, "error");
            break;
        default:
            break;
    }
}

function renderLine(entry) {
    const row = document.createElement("article");
    row.className = "console-line";
    row.dataset.source = entry.source;

    const timestamp = document.createElement("span");
    timestamp.className = "console-line-timestamp";
    timestamp.textContent = activeStatus?.ui.showTimestamps ? entry.timestamp : "";

    const level = document.createElement("span");
    level.className = "console-line-level";
    level.textContent = entry.level;

    const text = document.createElement("div");
    text.className = "console-line-text";
    text.textContent = entry.text;

    const copyButton = document.createElement("button");
    copyButton.className = "console-line-copy";
    copyButton.type = "button";
    copyButton.textContent = "copy";
    copyButton.addEventListener("click", async () => {
        try {
            await navigator.clipboard.writeText(entry.text);
            showToast("Line copied.");
        } catch {
            showToast("Copy failed.", "error");
        }
    });

    row.append(timestamp, level, text, copyButton);
    output.append(row);
}

function setConnectionState(label, className) {
    connectionBadge.textContent = label;
    connectionBadge.className = `badge ${className}`;
}

function stickToBottom(force) {
    if (force || autoScroll) {
        output.scrollTop = output.scrollHeight;
        scrollBadge.classList.add("hidden");
    }
}

function resizeInput() {
    commandInput.style.height = "0";
    commandInput.style.height = `${Math.min(commandInput.scrollHeight, 220)}px`;
}

commandInput.addEventListener("input", resizeInput);

commandInput.addEventListener("keydown", event => {
    if (event.key === "Enter" && !event.shiftKey) {
        event.preventDefault();
        commandForm.requestSubmit();
        return;
    }

    if (event.key === "ArrowUp" && commandHistory.length > 0 && commandInput.selectionStart === 0 && commandInput.selectionEnd === 0) {
        event.preventDefault();
        historyCursor = Math.min(commandHistory.length - 1, historyCursor + 1);
        commandInput.value = commandHistory[commandHistory.length - 1 - historyCursor];
        resizeInput();
    }

    if (event.key === "ArrowDown" && historyCursor >= 0) {
        event.preventDefault();
        historyCursor -= 1;
        commandInput.value = historyCursor >= 0 ? commandHistory[commandHistory.length - 1 - historyCursor] : "";
        resizeInput();
    }
});

commandForm.addEventListener("submit", event => {
    event.preventDefault();

    const command = commandInput.value.trimEnd();
    if (!command) {
        return;
    }

    if (!socket || socket.readyState !== WebSocket.OPEN) {
        showToast("Socket is not connected.", "error");
        return;
    }

    commandHistory.push(command);
    if (commandHistory.length > 100) {
        commandHistory = commandHistory.slice(-100);
    }
    historyCursor = -1;

    socket.send(JSON.stringify({ type: "command", command }));
    commandInput.value = "";
    resizeInput();
    inlineStatus.textContent = "Command sent to the server console.";
});

output.addEventListener("scroll", () => {
    const distanceFromBottom = output.scrollHeight - output.scrollTop - output.clientHeight;
    autoScroll = distanceFromBottom < 24;
    scrollBadge.classList.toggle("hidden", autoScroll);
});

reconnectButton.addEventListener("click", () => {
    if (socket) {
        socket.close();
    }
    inlineStatus.textContent = "Reconnecting…";
    closePanel();
    connectSocket();
});

clearButton.addEventListener("click", () => {
    output.replaceChildren();
    inlineStatus.textContent = "Local console view cleared.";
    closePanel();
});

wrapButton.addEventListener("click", () => {
    output.classList.toggle("wrap-enabled");
    closePanel();
});

logoutButton.addEventListener("click", async () => {
    try {
        await fetchJson("/api/auth/logout", { method: "POST", body: JSON.stringify({}) });
        window.location.replace("/login");
    } catch (error) {
        inlineStatus.textContent = error.message;
        showToast(error.message, "error");
    }
});

panelToggle.addEventListener("click", () => {
    const isOpen = appShell.dataset.panelOpen === "true";
    setPanelOpen(!isOpen);
});

railToggle.addEventListener("click", () => {
    setRailCollapsed(appShell.dataset.railCollapsed !== "true");
});

panelBackdrop.addEventListener("click", () => {
    closePanel();
});

output.addEventListener("pointerdown", () => {
    closePanel();
});

window.addEventListener("keydown", event => {
    if (event.key === "Escape") {
        closePanel();
    }
});

window.addEventListener("resize", () => {
    if (!isCompactLayout()) {
        setPanelOpen(false);
    }
});
