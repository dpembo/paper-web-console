import { fetchJson, showToast } from "/assets/shared.js";

const form = document.getElementById("loginForm");
const passwordInput = document.getElementById("passwordInput");
const statusNode = document.getElementById("loginStatus");

boot();

async function boot() {
    try {
        const status = await fetchJson("/api/status", { method: "GET" });
        if (!status.configured) {
            window.location.replace("/setup");
            return;
        }
        if (status.authenticated) {
            window.location.replace("/");
            return;
        }
        if (status.ui.brandLogoUrl) {
            document.querySelector(".brand-mark").src = status.ui.brandLogoUrl;
        }
        if (status.ui.brandEyebrow) {
            document.querySelector(".auth-copy .eyebrow").textContent = status.ui.brandEyebrow;
        }
        statusNode.textContent = `${status.serverName} ${status.minecraftVersion} is waiting for authentication.`;
    } catch (error) {
        statusNode.textContent = error.message;
        showToast(error.message, "error");
    }
}

form.addEventListener("submit", async event => {
    event.preventDefault();
    statusNode.textContent = "Verifying password…";

    try {
        await fetchJson("/api/auth/login", {
            method: "POST",
            body: JSON.stringify({ password: passwordInput.value })
        });

        statusNode.textContent = "Authenticated. Opening console…";
        window.location.replace("/");
    } catch (error) {
        statusNode.textContent = error.message;
        showToast(error.message, "error");
        passwordInput.select();
    }
});
