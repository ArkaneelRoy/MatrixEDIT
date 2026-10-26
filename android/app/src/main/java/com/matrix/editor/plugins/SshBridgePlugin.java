package com.matrix.editor.plugins;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Vector;

@CapacitorPlugin(name = "SshBridge")
public class SshBridgePlugin extends Plugin {
    private final JSch jsch = new JSch();
    private Session session;
    private ChannelSftp sftpChannel;
    private ChannelShell shellChannel;
    private OutputStream shellOut;

    @PluginMethod
    public void connect(PluginCall call) {
        String host = call.getString("host", "127.0.0.1");
        int port = call.getInt("port", 8022);
        String user = call.getString("username");
        String pass = call.getString("password");

        new Thread(() -> {
            try {
                // Close existing session if active
                disconnectChannels();

                session = jsch.getSession(user, host, port);
                session.setPassword(pass);
                session.setConfig("StrictHostKeyChecking", "no");
                session.connect(15000);

                // Open SFTP Channel
                Channel sftp = session.openChannel("sftp");
                sftp.connect(10000);
                sftpChannel = (ChannelSftp) sftp;

                // Open PTY Shell Channel
                Channel shell = session.openChannel("shell");
                shellChannel = (ChannelShell) shell;
                shellChannel.setPtyType("xterm-256color");
                
                InputStream shellIn = shellChannel.getInputStream();
                shellOut = shellChannel.getOutputStream();
                shellChannel.connect(10000);

                // Pipe incoming shell bytes back to JS frontend
                listenToShell(shellIn);

                call.resolve();
            } catch (Exception e) {
                call.reject("SSH Connection Failed: " + e.getMessage());
            }
        }).start();
    }

    private void listenToShell(InputStream in) {
        new Thread(() -> {
            byte[] buffer = new byte[2048];
            int read;
            try {
                while ((read = in.read(buffer)) != -1) {
                    String text = new String(buffer, 0, read, StandardCharsets.UTF_8);
                    JSObject data = new JSObject();
                    data.put("chunk", text);
                    notifyListeners("terminalData", data);
                }
            } catch (IOException ignored) {}
        }).start();
    }

    @PluginMethod
    public void sendTerminalInput(PluginCall call) {
        String data = call.getString("data", "");
        try {
            if (shellOut != null) {
                shellOut.write(data.getBytes(StandardCharsets.UTF_8));
                shellOut.flush();
                call.resolve();
            } else {
                call.reject("Shell not connected");
            }
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void readDir(PluginCall call) {
        String path = call.getString("path", ".");
        new Thread(() -> {
            try {
                Vector<ChannelSftp.LsEntry> entries = sftpChannel.ls(path);
                JSObject ret = new JSObject();
                JSArray array = new JSArray();

                for (ChannelSftp.LsEntry entry : entries) {
                    String name = entry.getFilename();
                    if (name.equals(".") || name.equals("..")) continue;

                    JSObject item = new JSObject();
                    item.put("name", name);
                    item.put("isDirectory", entry.getAttrs().isDir());
                    array.put(item);
                }
                ret.put("files", array);
                call.resolve(ret);
            } catch (Exception e) {
                call.reject(e.getMessage());
            }
        }).start();
    }

    @PluginMethod
    public void readFile(PluginCall call) {
        String path = call.getString("path");
        new Thread(() -> {
            try {
                InputStream is = sftpChannel.get(path);
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                reader.close();

                JSObject ret = new JSObject();
                ret.put("content", sb.toString());
                call.resolve(ret);
            } catch (Exception e) {
                call.reject(e.getMessage());
            }
        }).start();
    }

    @PluginMethod
    public void writeFile(PluginCall call) {
        String path = call.getString("path");
        String content = call.getString("content", "");
        new Thread(() -> {
            try {
                ByteArrayInputStream in = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
                sftpChannel.put(in, path, ChannelSftp.OVERWRITE);
                in.close();
                call.resolve();
            } catch (Exception e) {
                call.reject(e.getMessage());
            }
        }).start();
    }

    @PluginMethod
    public void createFile(PluginCall call) {
        String path = call.getString("path");
        new Thread(() -> {
            try {
                ByteArrayInputStream empty = new ByteArrayInputStream(new byte[0]);
                sftpChannel.put(empty, path, ChannelSftp.OVERWRITE);
                empty.close();
                call.resolve();
            } catch (Exception e) {
                call.reject("Failed to create file: " + e.getMessage());
            }
        }).start();
    }

    @PluginMethod
    public void mkdir(PluginCall call) {
        String path = call.getString("path");
        new Thread(() -> {
            try {
                sftpChannel.mkdir(path);
                call.resolve();
            } catch (Exception e) {
                call.reject("Failed to create directory: " + e.getMessage());
            }
        }).start();
    }

    @PluginMethod
    public void rename(PluginCall call) {
        String oldPath = call.getString("oldPath");
        String newPath = call.getString("newPath");
        new Thread(() -> {
            try {
                sftpChannel.rename(oldPath, newPath);
                call.resolve();
            } catch (Exception e) {
                call.reject("Failed to rename: " + e.getMessage());
            }
        }).start();
    }

    @PluginMethod
    public void delete(PluginCall call) {
        String path = call.getString("path");
        boolean isDir = call.getBoolean("isDirectory", false);
        new Thread(() -> {
            try {
                if (isDir) {
                    ChannelExec execChannel = (ChannelExec) session.openChannel("exec");
                    execChannel.setCommand("rm -rf \"" + path + "\"");
                    execChannel.connect();
                    while (!execChannel.isClosed()) {
                        Thread.sleep(50);
                    }
                    execChannel.disconnect();
                } else {
                    sftpChannel.rm(path);
                }
                call.resolve();
            } catch (Exception e) {
                call.reject("Failed to delete: " + e.getMessage());
            }
        }).start();
    }

    private void disconnectChannels() {
        try {
            if (sftpChannel != null && sftpChannel.isConnected()) sftpChannel.disconnect();
            if (shellChannel != null && shellChannel.isConnected()) shellChannel.disconnect();
            if (session != null && session.isConnected()) session.disconnect();
        } catch (Exception ignored) {}
    }
}
