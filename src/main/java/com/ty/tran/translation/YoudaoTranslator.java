// YoudaoTranslator.java
package com.ty.tran.translation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class YoudaoTranslator {
    private static final Logger logger = LoggerFactory.getLogger(YoudaoTranslator.class);

    private final String appKey;
    private final String appSecret;
    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;

    private static final String API_URL = "https://openapi.youdao.com/api";

    public YoudaoTranslator(String appKey, String appSecret) {
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.httpClient = HttpClients.createDefault();
        this.objectMapper = new ObjectMapper();
    }

    public String translate(String text, String from, String to) {
        if (text == null || text.trim().isEmpty()) {
            return text;
        }

        try {
            // 保护变量和转义字符
            String protectedText = protectSpecialContent(text);

            Map<String, String> params = new HashMap<>();
            String salt = String.valueOf(System.currentTimeMillis());
            params.put("from", from);
            params.put("to", to);
            params.put("signType", "v3");
            params.put("curtime", String.valueOf(System.currentTimeMillis() / 1000));
            params.put("appKey", appKey);
            params.put("q", protectedText);
            params.put("salt", salt);
            params.put("sign", generateSign(protectedText, salt, params.get("curtime")));

            String result = doPost(API_URL, params);
            JsonNode jsonNode = objectMapper.readTree(result);

            if (jsonNode.has("errorCode") && "0".equals(jsonNode.get("errorCode").asText())) {
                String translation = jsonNode.get("translation").get(0).asText();
                // 恢复保护的变量和转义字符
                return restoreSpecialContent(translation);
            } else {
                logger.error("Translation failed: {}", result);
                return text; // 翻译失败返回原文本
            }
        } catch (Exception e) {
            logger.error("Translation error for text: {}", text, e);
            return text;
        }
    }

    private String generateSign(String text, String salt, String curtime) {
        String input = text.length() > 20 ?
                text.substring(0, 10) + text.length() + text.substring(text.length() - 10) :
                text;
        String str = appKey + input + salt + curtime + appSecret;
        return encrypt(str);
    }

    private String encrypt(String str) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(str.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : digest) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    private String doPost(String url, Map<String, String> params) throws IOException {
        HttpPost httpPost = new HttpPost(url);
        List<NameValuePair> paramList = new ArrayList<>();

        for (Map.Entry<String, String> entry : params.entrySet()) {
            paramList.add(new BasicNameValuePair(entry.getKey(), entry.getValue()));
        }

        httpPost.setEntity(new UrlEncodedFormEntity(paramList, StandardCharsets.UTF_8));

        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            HttpEntity entity = response.getEntity();
            return entity != null ? EntityUtils.toString(entity, StandardCharsets.UTF_8) : null;
        }
    }

    // 保护变量和转义字符不被翻译
    private String protectSpecialContent(String text) {
        // 保护 {变量}
        String protectedText = text.replaceAll("\\{([^}]+)\\}", "___VAR_$1___");
        // 保护 HTML 转义字符
        protectedText = protectedText.replaceAll("&[a-z]+;", "___HTML_ENTITY___");
        protectedText = protectedText.replaceAll("&#[0-9]+;", "___HTML_ENTITY___");
        return protectedText;
    }

    // 恢复保护的变量和转义字符
    private String restoreSpecialContent(String text) {
        String restoredText = text.replaceAll("___VAR_([^_]+)___", "{$1}");
        restoredText = restoredText.replaceAll("___HTML_ENTITY___", "&amp;"); // 恢复为 &amp;
        return restoredText;
    }

    public void close() {
        try {
            httpClient.close();
        } catch (IOException e) {
            logger.error("Error closing HTTP client", e);
        }
    }
}