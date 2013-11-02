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
                public void run() {
                    process(s);
                }
            });
            t.start();
        }
        
        ss.close();
    }
    
    private static void process(Socket s) {
        BufferedReader br = null;
        BufferedInputStream bis = null;
        BufferedOutputStream bos = null;
        try {
            br = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            bos = new BufferedOutputStream(s.getOutputStream());
            
            String line = br.readLine();
            if (line != null) {
                String[] parts = line.split("\\s+");
                String method = parts[0];
                if ("GET".equalsIgnoreCase(method)) {
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
                    
                    bis = new BufferedInputStream(new FileInputStream(f));
                    
                    sendLine(bos, "HTTP/1.1 200 OK");
                    sendLine(bos, "Content-Length: " + f.length());
                    sendLine(bos, "");
                    copy(bis, bos);
    
                    bos.flush();
                } else {
                    sendErrorResponse(bos, 405, "Method not allowed: " + method, HEADERS_405);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            close(bis);
            close(bos);
            close(br);
            close(s);
        }
    }
    
    private static void sendGeneratedIndexHtmlResponse(OutputStream os, File directory, Map<String, String> headers) throws IOException {
        
        sendLine(os, "HTTP/1.1 200 OK");
        
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                sendLine(os, entry.getKey() + ": " + entry.getValue());
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
                if (link.startsWith("/"))
                    link = link.substring(1);
                sendLine(baos, "<li><a href='" + link + "'>" + link + "</a></li>");
            }
            sendLine(baos, "</ul>");
            sendLine(baos, "</body>");
            sendLine(baos, "</html>");
        }
        
        sendLine(os, "Content-Length: " + baos.size());
        sendLine(os, "");
        os.write(baos.toByteArray());
        os.flush();
    }
    
    private static void sendErrorResponse(OutputStream os, int errorCode, String reason, Map<String, String> headers) throws IOException {
        sendLine(os, "HTTP/1.1 " + errorCode + " " + reason);
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                sendLine(os, entry.getKey() + ": " + entry.getValue());
            }
        }
        sendLine(os, "");
        os.flush();
    }
    
    private static void sendLine(OutputStream os, String line) throws IOException {
        os.write((line + "\n").getBytes("UTF-8"));
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
