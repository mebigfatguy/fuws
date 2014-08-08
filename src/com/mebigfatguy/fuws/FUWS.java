/*
 * fuws - A very lightweight web server.
 * Copyright (C) 2013 Dave Brosius
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
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;


public class FUWS {

    private static File DIRECTORY;
    
    private static Map<String, String> HEADERS_405 = new HashMap<String, String>();
    private static Map<String, String> HEADERS_INDEX = new HashMap<String, String>();
    static {
        HEADERS_405.put("Accept", "GET");
        HEADERS_INDEX.put("MimeType", "text/html");
    }
    
    public static void main(String[] args) throws IOException {
        
        if (args.length < 2) {
            System.out.println("FUWS directory port");
            System.exit(-1);
        }
        
        DIRECTORY = new File(args[0]);
        int port = Integer.parseInt(args[1]);
        
        ServerSocket ss = new ServerSocket(port);
        
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
        
        ss.close();
    }
    
    private static void process(Socket s) {

        try (BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             BufferedOutputStream bos = new BufferedOutputStream(s.getOutputStream())) {
            
            String line = br.readLine();
            if (line != null) {
                String[] parts = line.split("\\s+");
                String method = parts[0];
                Map<String, String> headers = readHeaders(br);
                if ("HEAD".equalsIgnoreCase(method) || "GET".equalsIgnoreCase(method)) {
                    String path = parts[1];
                    if (path.startsWith("/")) {
                        path = path.substring(1);
                    }
                    File f = new File(DIRECTORY, path);
                    if (f.isDirectory() && f.exists()) {
                        sendGeneratedIndexHtmlResponse(bos, f, HEADERS_INDEX);
                        return;
                    } else if (!f.exists()) {
                        sendErrorResponse(bos, 404, "File Not Found", null);
                        return;
                    }
                    
                    sendResponseHeader(bos, f.length());
                    if ("GET".equalsIgnoreCase(method)) {
                    	try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(f))) {     
                        	copy(bis, bos);
                        }
                    } else {
                    	sendResponseHeader(bos, f.length());
                    }
                    
                    bos.flush();
                } else {
                    sendErrorResponse(bos, 405, String.format("Method not allowed: %s",  method), HEADERS_405);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private static Map<String, String> readHeaders(BufferedReader br) throws IOException {
    	Map<String, String> headers = new HashMap<String, String>();
    	String line = br.readLine();
    	while (!line.isEmpty()) {
    		int colonPos = line.indexOf(':');
    		headers.put(line.substring(0, colonPos).trim(), line.substring(colonPos+1).trim());
    		line = br.readLine();
    	}
    	return headers;
    }
    
    private static void sendResponseHeader(OutputStream os, long length) throws IOException {
    	sendLine(os, "HTTP/1.1 200 OK");
        sendLine(os, String.format("Content-Length: %d", length));
        sendLine(os, "");
    }
    
    private static void sendGeneratedIndexHtmlResponse(OutputStream os, File directory, Map<String, String> headers) throws IOException {
        
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
            File[] files = directory.listFiles();        
            for (File f : files) {
                String link = f.getPath().substring(DIRECTORY.getPath().length());
                sendLine(baos, String.format("<li><a href='%s'>%s</a></li>", link, link));
            }
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
    
    private static void close(Closeable c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (Exception e) {       
        }
    }
}
