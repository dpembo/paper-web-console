package dev.dimo.paperwebconsole.config;

import org.bukkit.configuration.file.FileConfiguration;

public record PluginConfiguration(
    String bindAddress,
    int port,
    int historyLimit,
    int sessionHours,
    int setupTokenTtlMinutes,
    int loginRateLimitWindowMinutes,
    int loginRateLimitMaxAttempts,
    UiConfiguration ui
) {
    public static PluginConfiguration from(FileConfiguration fileConfiguration) {
        String bindAddress = requireText(fileConfiguration.getString("bindAddress", "0.0.0.0"), "bindAddress");
        int port = requireRange(fileConfiguration.getInt("port", 28765), 1, 65535, "port");
        int historyLimit = requireRange(fileConfiguration.getInt("historyLimit", 250), 25, 10_000, "historyLimit");
        int sessionHours = requireRange(fileConfiguration.getInt("sessionHours", 12), 1, 168, "sessionHours");
        int setupTokenTtlMinutes = requireRange(fileConfiguration.getInt("setupTokenTtlMinutes", 30), 5, 1_440, "setupTokenTtlMinutes");
        int loginRateLimitWindowMinutes = requireRange(fileConfiguration.getInt("loginRateLimitWindowMinutes", 15), 1, 1_440, "loginRateLimitWindowMinutes");
        int loginRateLimitMaxAttempts = requireRange(fileConfiguration.getInt("loginRateLimitMaxAttempts", 5), 2, 50, "loginRateLimitMaxAttempts");
        String brandLogoFile = validateBrandLogoFile(fileConfiguration.getString("ui.brandLogoFile", ""));
        String brandEyebrow = trimOrNull(fileConfiguration.getString("ui.brandEyebrow", ""));
        String brandTitle = trimOrNull(fileConfiguration.getString("ui.brandTitle", ""));
        String customCssFile = validateCssFile(fileConfiguration.getString("ui.customCssFile", ""));
        UiConfiguration ui = new UiConfiguration(
            fileConfiguration.getBoolean("ui.showTimestamps", true),
            requireRange(fileConfiguration.getInt("ui.maxBufferedLines", 2_000), 200, 20_000, "ui.maxBufferedLines"),
            fileConfiguration.getBoolean("ui.defaultWrapMode", true),
            brandLogoFile,
            brandEyebrow,
            brandTitle,
            customCssFile
        );

        return new PluginConfiguration(
            bindAddress,
            port,
            historyLimit,
            sessionHours,
            setupTokenTtlMinutes,
            loginRateLimitWindowMinutes,
            loginRateLimitMaxAttempts,
            ui
        );
    }

    private static String trimOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private static String validateCssFile(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (!trimmed.matches("[a-zA-Z0-9_\\-]+\\.css")) {
            throw new IllegalArgumentException("ui.customCssFile must be a plain .css filename (e.g. custom.css).");
        }
        return trimmed;
    }

    private static String validateBrandLogoFile(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (!trimmed.matches("[a-zA-Z0-9_\\-]+\\.[a-zA-Z]{2,5}")) {
            throw new IllegalArgumentException("ui.brandLogoFile must be a plain filename with a recognised image extension (e.g. logo.png).");
        }
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith(".png") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg")
                && !lower.endsWith(".gif") && !lower.endsWith(".svg") && !lower.endsWith(".webp")) {
            throw new IllegalArgumentException("ui.brandLogoFile must be a .png, .jpg, .jpeg, .gif, .svg, or .webp file.");
        }
        return trimmed;
    }

    private static String requireText(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " must not be blank.");
        }
        return value.trim();
    }

    private static int requireRange(int value, int minimum, int maximum, String key) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum + ".");
        }
        return value;
    }

    public record UiConfiguration(
        boolean showTimestamps,
        int maxBufferedLines,
        boolean defaultWrapMode,
        String brandLogoFile,
        String brandEyebrow,
        String brandTitle,
        String customCssFile
    ) {
    }
}
