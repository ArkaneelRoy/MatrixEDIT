package com.matrix.editor.plugins;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.jcraft.jsch.*;

import java.io.*;
import java.util.Vector;

@CapacitorPlugin(name = "SshBridge")
public class SshBridgePlugin extends Plugin {
    private JSch jsch = new JSch();
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
                session = jsch.getSession(user, host, port);
                session.setPassword(pass);
                session.setConfig("StrictHostKeyChecking", "no");
                session.connect(15000);

                // Open SFTP
                Channel channel = session.openChannel("sftp");
                channel.connect();
                sftpChannel = (ChannelSftp) channel;

                // Open PTY Shell
                shellChannel = (ChannelShell) session.openChannel("shell");
                shellChannel.setPtyType("xterm-256color");
                InputStream shellIn = shellChannel.getInputStream();
                shellOut = shellChannel.getOutputStream();
                shellChannel.connect();

                // Pipe incoming shell bytes back to JS
                listenToShell(shellIn);

                call.resolve();
            } catch (Exception e) {
                call.reject("SSH Connection Failed: " + e.getMessage());
            }
        }).start();
    }

    private void listenToShell(InputStream in) {
        new Thread(() -> {
            byte[] buffer = new byte[1024];
            int read;
            try {
                while ((read = in.read(buffer)) != -1) {
                    String text = new String(buffer, 0, read);
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
                shellOut.write(data.getBytes());
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
                com.getcapacitor.JSArray array = new com.getcapacitor.JSArray();
                for (ChannelSftp.LsEntry entry : entries) {
                    if (entry.getFilename().equals(".") || entry.getFilename().equals("..")) continue;
                    JSObject item = new JSObject();
                    item.put("name", entry.getFilename());
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
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
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
        String content = call.getString("content");
        new Thread(() -> {
            try {
                ByteArrayInputStream in = new ByteArrayInputStream(content.getBytes());
                sftpChannel.put(in, path, ChannelSftp.OVERWRITE);
                call.resolve();
            } catch (Exception e) {
                call.reject(e.getMessage());
            }
        }).start();
    }
}
