package com.mojang.minecraft.server;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

final class HeartbeatThread extends Thread {

    private final MinecraftServer server;
    private final String args;

    HeartbeatThread(MinecraftServer server, String args) {
        this.server = server;
        this.args = args;
    }

    public void run() {
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) new URL(server.heartbeatServer).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setRequestProperty("Content-Length", Integer.toString(this.args.getBytes().length));
            connection.setRequestProperty("Content-Language", "en-US");
            connection.setUseCaches(false);
            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.connect();

            DataOutputStream outputStream = new DataOutputStream(connection.getOutputStream());
            outputStream.writeBytes(this.args);
            outputStream.flush();
            outputStream.close();

            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String url = reader.readLine();
            if (!MinecraftServer.getExternalUrl(this.server).equals(url)) {
                MinecraftServer.logger.info("To connect directly to this server, surf to: " + url);
                PrintWriter writer = new PrintWriter(new FileWriter("externalurl.txt"));
                writer.println(url);
                writer.close();

                MinecraftServer.logger.info("(This is also in externalurl.txt)");
                MinecraftServer.setExternalUrl(this.server, url);
            }

            reader.close();
        } catch (Exception e) {
            MinecraftServer.logger.severe("Failed to assemble heartbeat: " + e);
            e.printStackTrace();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
