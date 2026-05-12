// BaiduTranslator.java
package com.ty.tran.translation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
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
import java.util.stream.Collectors;

@Slf4j
public class BaiduTranslator {
    private static final Logger log = LoggerFactory.getLogger(BaiduTranslator.class);

    private final String appId;
    private final String appKey;
    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;

    private static final String API_URL = "https://fanyi-api.baidu.com/api/trans/vip/translate";
    // 批量翻译，最多支持2000字符或100条文本
    public List<String> translateBatch(List<String> textList, String from, String to) {
        if (textList == null || textList.isEmpty()) {
            return textList;
        }

        // 检查总字符数（百度API限制单次最多2000字符）
        int totalChars = textList.stream().mapToInt(String::length).sum();
        if (totalChars > 2000) {
            // 如果超过限制，分批处理
            return translateInBatches(textList, from, to);
        }

        try {
            // 保护所有文本
            List<String> protectedTexts = textList.stream()
                    .map(this::protectSpecialContent)
                    .collect(Collectors.toList());

            String combinedText = String.join("\n", protectedTexts);
            String salt = String.valueOf(System.currentTimeMillis());
            String sign = generateSign(combinedText, salt);

            Map<String, String> params = new HashMap<>();
            params.put("q", combinedText);
            params.put("from", from);
            params.put("to", to);
            params.put("appid", appId);
            params.put("salt", salt);
            params.put("sign", sign);

            String result = doPost(API_URL, params);
            JsonNode jsonNode = objectMapper.readTree(result);

            if (jsonNode.has("trans_result")) {
                JsonNode transResult = jsonNode.get("trans_result");
                List<String> translations = new ArrayList<>();
                for (JsonNode item : transResult) {
                    String translation = item.get("dst").asText();
                    translations.add(restoreSpecialContent(translation));
                }
                return translations;
            }

            // 失败时返回原文本
            return textList;

        } catch (Exception e) {
            log.error("Batch translation error", e);
            return textList;
        }
    }

    private List<String> translateInBatches(List<String> texts, String from, String to) {
        List<String> allResults = new ArrayList<>();
        List<String> currentBatch = new ArrayList<>();
        int currentBatchChars = 0;

        for (String text : texts) {
            if (currentBatchChars + text.length() > 1800 || currentBatch.size() >= 50) {
                // 发送当前批次
                List<String> batchResults = translateBatch(currentBatch, from, to);
                allResults.addAll(batchResults);

                // 重置批次
                currentBatch.clear();
                currentBatchChars = 0;

                // 批次间延迟
                try {
                    Thread.sleep(1100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            currentBatch.add(text);
            currentBatchChars += text.length();
        }

        // 处理最后一批
        if (!currentBatch.isEmpty()) {
            List<String> batchResults = translateBatch(currentBatch, from, to);
            allResults.addAll(batchResults);
        }

        return allResults;
    }
    public BaiduTranslator(String appId, String appKey) {
        this.appId = appId;
        this.appKey = appKey;
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

            // 生成随机数
            String salt = String.valueOf(System.currentTimeMillis());

            // 生成签名
            String sign = generateSign(protectedText, salt);

            // 构建请求参数
            Map<String, String> params = new HashMap<>();
            params.put("q", protectedText);
            params.put("from", from);
            params.put("to", to);
            params.put("appid", appId);
            params.put("salt", salt);
            params.put("sign", sign);

            String result = doPost(API_URL, params);
            JsonNode jsonNode = objectMapper.readTree(result);

            // 检查响应
            if (jsonNode.has("trans_result")) {
                JsonNode transResult = jsonNode.get("trans_result");
                if (transResult.isArray() && transResult.size() > 0) {
                    String translation = transResult.get(0).get("dst").asText();
                    // 恢复保护的变量和转义字符
                    return restoreSpecialContent(translation);
                }
            } else if (jsonNode.has("error_code")) {
                String errorCode = jsonNode.get("error_code").asText();
                String errorMsg = jsonNode.has("error_msg") ?
                        jsonNode.get("error_msg").asText() : "Unknown error";
                log.error("Baidu translation failed - Error {}: {}", errorCode, errorMsg);

                // 根据错误码决定是否重试或返回原文本
                if ("54003".equals(errorCode)) { // 访问频率限制
                    Thread.sleep(2000); // 等待2秒后返回原文本
                }
            }

            // 翻译失败返回原文本
            return text;

        } catch (Exception e) {
            log.error("Translation error for text: {}", text, e);
            return text;
        }
    }

    /**
     * 生成百度翻译API签名:cite[1]
     * sign = md5(appid + q + salt + appKey)
     */
    private String generateSign(String text, String salt) {
        String str = appId + text + salt + appKey;
        return md5(str);
    }

    private String md5(String str) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(str.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : digest) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not found", e);
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
        String protectedText = text;

        // 1. 保护大括号内的变量 {variable}
        protectedText = protectedText.replaceAll("\\{([^}]+)\\}", "___VAR_$1___");

        // 2. 保护 HTML 标签和转义字符
        protectedText = protectedText.replaceAll("&[a-z]+;", "___HTML_ENTITY___");
        protectedText = protectedText.replaceAll("&#[0-9]+;", "___HTML_ENTITY___");
        protectedText = protectedText.replaceAll("&amp;", "___HTML_ENTITY___");

        // 3. 保护 HTML 标签 <tag>
        protectedText = protectedText.replaceAll("</?[a-zA-Z][^>]*>", "___HTML_TAG___");

        // 4. 保护单引号内的内容 '#a70404'
        protectedText = protectedText.replaceAll("'[^']*'", "___SINGLE_QUOTE___");

        // 5. 保护斜杠后面的内容 /font
        protectedText = protectedText.replaceAll("/[a-zA-Z]+", "___SLASH_CONTENT___");

        return protectedText;
    }

    // 恢复保护的变量和转义字符
    private String restoreSpecialContent(String text) {
        String restoredText = text;

        // 按相反顺序恢复
        restoredText = restoredText.replaceAll("___SLASH_CONTENT___", "/font");
        restoredText = restoredText.replaceAll("___SINGLE_QUOTE___", "'#a70404'");
        restoredText = restoredText.replaceAll("___HTML_TAG___", "<font color='#a70404'>Hyper Aroused</font>");
        restoredText = restoredText.replaceAll("___HTML_ENTITY___", "&amp;");
        restoredText = restoredText.replaceAll("___VAR_([^_]+)___", "{$1}");

        return restoredText;
    }


    public void close() {
        try {
            httpClient.close();
        } catch (IOException e) {
            log.error("Error closing HTTP client", e);
        }
    }
}