package com.github.anirbanmu.wen;

import com.github.anirbanmu.wen.discord.Gateway;
import com.github.anirbanmu.wen.log.Log;

public class Main {
    public static void main(String[] args) {
        String token = System.getenv("DISCORD_TOKEN");
        if (token == null) {
            Log.error("startup.missing_token", "message", "DISCORD_TOKEN env var is required");
            System.exit(1);
        }

        Log.info("bot_startup", "status", "starting", "version", "1.0.0");

        Gateway gateway = new Gateway(token, interaction -> {
            Log.info("interaction.received", "id", interaction.id());
        });

        gateway.connect();
        Log.info("gateway.started");

        // keep main thread alive
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
