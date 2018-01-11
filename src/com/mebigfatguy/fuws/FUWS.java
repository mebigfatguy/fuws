/*
 * fuws - A very lightweight web server.
 * Copyright (C) 2013-2016 Dave Brosius
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package com.mebigfatguy.fuws;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

public class FUWS {

    private static Path DIRECTORY;

    private static Map<String, String> HEADERS_405 = new HashMap<>();
    private static Map<String, String> HEADERS_INDEX = new HashMap<>();
    static {
        HEADERS_405.put("Allow", "HEAD, GET");
        HEADERS_INDEX.put("MimeType", "text/html");
    }

    public static void main(String[] args) throws IOException {

        if (args.length < 2) {
            System.out.println("FUWS directory port");
            System.exit(-1);
        }

        DIRECTORY = Paths.get(args[0]);
        int port = Integer.parseInt(args[1]);

        try (ServerSocket ss = new ServerSocket(port)) {
            while (!Thread.interrupted()) {

                final Socket s = ss.accept();
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            process(s);
                        } finally {
                            close(s);
                        }
                    }
                });
                t.start();
            }
        }
    }

    private static void process(Socket s) {

        try (BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                OutputStream bos = new BufferedOutputStream(s.getOutputStream())) {

            String line = br.readLine();
            if (line != null) {
                String[] parts = line.split("\\s+");
                String method = parts[0];
                Map<String, String> headers = readHeaders(br);
                if ("HEAD".equalsIgnoreCase(method) || "GET".equalsIgnoreCase(method)) {
                    String path = parts[1];
                    while (path.startsWith("/")) {
                        path = path.substring(1);
                    }
                    path = URLDecoder.decode(path, StandardCharsets.UTF_8.name());
                    Path f = DIRECTORY.resolve(path);
                    if (Files.isDirectory(f) && Files.exists(f)) {
                        sendGeneratedIndexHtmlResponse(bos, f, HEADERS_INDEX);
                        return;
                    } else if (!Files.exists(f)) {
                        sendErrorResponse(bos, 404, "File Not Found", null);
                        return;
                    }

                    boolean gzip = accceptsGZIP(headers);
                    sendResponseHeader(bos, Files.size(f), gzip);
                    if ("GET".equalsIgnoreCase(method)) {
                        OutputStream os = gzip ? new GZIPOutputStream(bos) : bos;

                        try (BufferedInputStream bis = new BufferedInputStream(Files.newInputStream(f))) {
                            copy(bis, os);
                            if (gzip) {
                                ((GZIPOutputStream) os).finish();
                            }
                            os.flush();
                        }
                    } else {
                        sendResponseHeader(bos, Files.size(f), gzip);
                        bos.flush();
                    }

                } else {
                    sendErrorResponse(bos, 405, String.format("Method not allowed: %s", method), HEADERS_405);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static Map<String, String> readHeaders(BufferedReader br) throws IOException {
        Map<String, String> headers = new HashMap<>();
        String line = br.readLine();
        while (!line.isEmpty()) {
            int colonPos = line.indexOf(':');
            headers.put(line.substring(0, colonPos).trim(), line.substring(colonPos + 1).trim());
            line = br.readLine();
        }
        return headers;
    }

    private static void sendResponseHeader(OutputStream os, long length, boolean useGZip) throws IOException {
        sendLine(os, "HTTP/1.1 200 OK");
        if (useGZip) {
            sendLine(os, "Content-Encoding: gzip");
        } else {
            sendLine(os, String.format("Content-Length: %d", length));
        }
        sendLine(os, "");
    }

    private static void sendGeneratedIndexHtmlResponse(OutputStream os, Path directory, Map<String, String> headers) throws IOException {

        sendLine(os, "HTTP/1.1 200 OK");

        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                sendLine(os, String.format("%s: %s", entry.getKey(), entry.getValue()));
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        {
            sendLine(baos, "<html>");
            sendLine(baos, "<body>");
            sendLine(baos, "<ul>");

            Files.newDirectoryStream(directory).forEach(f -> {
                try {
                    String link = URLEncoder.encode(f.toString().substring(DIRECTORY.toString().length() + 1), StandardCharsets.UTF_8.name());
                    String name = f.getFileName().toString();
                    sendLine(baos, String.format("<li><a href='%s'>%s</a></li>", link, name));
                } catch (Exception e) {
                }
            });

            sendLine(baos, "</ul>");
            sendLine(baos, "</body>");
            sendLine(baos, "</html>");
        }

        sendLine(os, String.format("Content-Length: %s", baos.size()));
        sendLine(os, "");
        os.write(baos.toByteArray());
        os.flush();
    }

    private static void sendErrorResponse(OutputStream os, int errorCode, String reason, Map<String, String> headers) throws IOException {
        sendLine(os, String.format("HTTP/1.1 %d %s", errorCode, reason));
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                sendLine(os, String.format("%s: %s", entry.getKey(), entry.getValue()));
            }
        }
        sendLine(os, "");
        os.flush();
    }

    private static void sendLine(OutputStream os, String line) throws IOException {
        os.write((line + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void copy(InputStream is, OutputStream os) throws IOException {
        byte[] buffer = new byte[10000];

        int len = is.read(buffer);
        while (len >= 0) {
            os.write(buffer, 0, len);
            len = is.read(buffer);
        }
    }

    private static boolean accceptsGZIP(Map<String, String> headers) {
        String acEnc = headers.get("Accept-Encoding");
        if (acEnc == null) {
            return false;
        }

        String[] encodings = acEnc.split("\\s*,\\s*");
        for (String enc : encodings) {
            String[] parts = enc.split("\\s*;\\s*");
            if ("gzip".equals(parts[0])) {
                return (parts.length == 1) || (!"q=0".equals(parts[1]));
            }
        }
        return false;
    }

    private static void close(Closeable c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (Exception e) {
        }
    }
}
