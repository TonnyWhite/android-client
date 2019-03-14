package io.split.android.client.utils;

import com.google.common.base.Strings;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.StringEntity;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;


public class Utils {

    public static StringEntity toJsonEntity(Object obj) {
        String json = Json.toJson(obj);
        return Utils.toJsonEntity(json);
    }

    public static StringEntity toJsonEntity(String json) {
        StringEntity entity = null;
        try {
            entity = new StringEntity(json, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            Logger.e(e);
        }
        entity.setContentType("application/json");
        return entity;
    }


    public static void forceClose(CloseableHttpResponse response) {
        try {
            if (response != null) {
                response.close();
            }
        } catch (IOException e) {
            // ignore
        }
    }

    public static boolean isSplitServiceReachable(URI uri) {
        try {
            return Utils.isReachable(new URIBuilder(uri).setPath("/api/version").build());
        } catch (URISyntaxException e) {
            Logger.e("URI mal formed. Reachability function fails ", e);
        }
        return false;
    }

    public static boolean isReachable(URI uri) {
        int port = uri.getPort();
        String host = uri.getHost();
        if (port == -1) {
            String scheme = uri.getScheme();
            if (scheme.equals("http")) {
                port = 80;
            } else if (scheme.equals("https")) {
                port = 443;
            } else {
                return false;
            }
        }

        return isReachable(host, port);
    }

    public static boolean isReachable(String host, int port ) {
        return isReachable(host, port, 1500);
    }

    // TCP/HTTP/DNS (depending on the port, 53=DNS, 80=HTTP, etc.)
    public static boolean isReachable(String host, int port, int timeoutMs ) {
        try {
            Socket socket = new Socket();
            SocketAddress socketAddress = new InetSocketAddress(host, port);

            socket.connect(socketAddress, timeoutMs);
            socket.close();

            return true;
        } catch (IOException e) { return false; }
    }

    public static String sanitizeForFileName(String string) {
        if(string == null) {
            return "";
        }
        return string.replaceAll("[^a-zA-Z0-9\\.\\-]", "_");
    }

    private static String sanitizeForFolderName(String string) {
        if(string == null) {
            return "";
        }
        return string.replaceAll("[^a-zA-Z0-9]", "");
    }

    public static String convertApiKeyToFolder(String apiKey) {
        final int SALT_LENGTH = 29;
        final String SALT_PREFIX = "$2a$10$";
        final String CHAR_TO_FILL_SALT = "A";
        String sanitizedApiKey = sanitizeForFolderName(apiKey);
        StringBuilder salt = new StringBuilder(SALT_PREFIX);
        if (sanitizedApiKey.length() >= SALT_LENGTH - SALT_PREFIX.length()) {
            salt.append(sanitizedApiKey.substring(0, SALT_LENGTH - SALT_PREFIX.length()));
        } else {
            salt.append(sanitizedApiKey);
            salt.append(Strings.repeat(CHAR_TO_FILL_SALT, (SALT_LENGTH - SALT_PREFIX.length()) - sanitizedApiKey.length()));
        }
        // Remove last end of strings
        String cleanedSalt = salt.toString().substring(0, 29);
        String hash = BCrypt.hashpw(sanitizedApiKey, cleanedSalt);

        return (hash != null ? sanitizeForFolderName(hash) : null);
    }
}
