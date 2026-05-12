// GoogleTranslator.java - 最终修复版本
package com.ty.tran.translation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class GoogleTranslator {
    private static final Logger logger = LoggerFactory.getLogger(GoogleTranslator.class);
    // Ollama 部署地址和模型
    // 注意：请根据您的实际 Ollama 配置修改 URL 和 MODEL_NAME
    private static final String OLLAMA_URL = "http://localhost:11434/api/generate";
    private static final String MODEL_NAME = "gemma3:12b";
    // private static final String MODEL_NAME = "llama3:8b"; // 示例模型

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.MINUTES) // 延长超时时间以应对大批量翻译
            .writeTimeout(5, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES)
            .build();
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    // 用于保护特殊内容不被翻译模型破坏
    private static final String PREFIX = "___PROTECT_";
    private static final String SUFFIX = "___";
    private static int counter = 0; // 用于生成唯一的占位符

    /**
     * 【新增】Ollama API 请求的数据结构 (DTO)
     */
    private static class OllamaRequest {
        public String model = MODEL_NAME;
        public String prompt;
        public boolean stream = false;

        public OllamaRequest(String prompt) {
            this.prompt = prompt;
        }

        // 默认构造函数，供 Jackson 使用
        public OllamaRequest() {}
    }
    /**
     * 批量翻译文本列表。
     * 实际通过调用一个批处理的 Ollama 请求来实现。
     *
     * @param texts 待翻译的英文文本列表。
     * @return 翻译后的中文文本列表，顺序与输入一致。
     */
    public List<String> translateBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. 保护特殊内容并准备翻译文本
        List<Map<String, String>> replacementMaps = new ArrayList<>();
        List<String> protectedTexts = texts.stream()
                .map(text -> {
                    Map<String, String> replacements = new LinkedHashMap<>();
                    String protectedText = protectSpecialContent(text, replacements);
                    replacementMaps.add(replacements);
                    return protectedText;
                })
                .collect(Collectors.toList());

        // 2. 构建包含所有文本的翻译请求
        String combinedText = String.join(" ||| ", protectedTexts);

        // 3. 构建 Prompt
        String systemPrompt = "你是一个专业的文件翻译工具。请将我提供的所有文本翻译成简洁、流畅的中文。翻译时，请严格遵守以下规则：\n" +
                "1. **保留所有**被保护的占位符（例如：`___PROTECT_TAG_0___`、`___PROTECT_VAR_1___`），**不要翻译、删除或改变它们的格式和顺序**。\n" +
                "2. **严格保留原文的换行符和段落格式**，**请勿将多行内容合并为单行**。尊重原文的语气和句式。"; // 【关键修改】：明确要求保留换行和段落格式

        String fullPrompt = String.format("%s\n\n请将以下用 ` ||| ` 分隔的文本段翻译成中文，并用相同的 ` ||| ` 分隔翻译结果：\n\n%s",
                systemPrompt, combinedText);

        String translatedCombinedText = callOllamaApi(fullPrompt);

        if (translatedCombinedText == null) {
            logger.error("Ollama API 调用失败，返回空结果。");
            // 返回原始文本作为失败的翻译结果
            return new ArrayList<>(texts);
        }

        // 4. 解析翻译结果
        // 【修改点 1】：解析翻译结果时，先去除前后多余的空白字符，再对每一项进行额外的清理。
        List<String> translatedTexts = Arrays.stream(translatedCombinedText.split(" \\|\\|\\| ", -1))
                .map(text -> text.trim()) // 移除字符串两端的空白字符
                .map(text -> {
                    // 移除开头多余的换行符和潜在的分隔符残留
                    String cleaned = text.replaceAll("^[\\r\\n\\s]*", "");
                    // 如果 LLM 倾向于在第一个结果前添加 |||，这里可以进一步处理
                    if (cleaned.startsWith("|||")) {
                        cleaned = cleaned.substring(3).replaceAll("^[\\r\\n\\s]*", "");
                    }
                    return cleaned;
                })
                .collect(Collectors.toList());

        // 5. 恢复特殊内容
        List<String> finalTranslations = new ArrayList<>();
        int size = Math.min(translatedTexts.size(), replacementMaps.size());

        for (int i = 0; i < size; i++) {
            String restoredText = restoreSpecialContent(translatedTexts.get(i), replacementMaps.get(i));
            finalTranslations.add(restoredText);
        }

        // 处理翻译结果数量与输入不匹配的情况
        if (finalTranslations.size() != texts.size()) {
            logger.warn("翻译结果数量与输入数量不匹配。输入: {}，输出: {}。返回部分结果并用原文填充。", texts.size(), finalTranslations.size());
            while (finalTranslations.size() < texts.size()) {
                finalTranslations.add(texts.get(finalTranslations.size()));
            }
            if (finalTranslations.size() > texts.size()) {
                finalTranslations = finalTranslations.subList(0, texts.size());
            }
        }

        return finalTranslations;
    }

    /**
     * 调用 Ollama API 进行翻译。
     * 【修复】使用 ObjectMapper 自动序列化请求，解决手动转义导致的 400 Bad Request 错误。
     */
    private String callOllamaApi(String prompt) {
        try {
            // 使用 ObjectMapper 将请求对象序列化为 JSON 字符串
            OllamaRequest requestObject = new OllamaRequest(prompt);
            String jsonPayload = mapper.writeValueAsString(requestObject);

            RequestBody body = RequestBody.create(jsonPayload, JSON);
            Request request = new Request.Builder()
                    .url(OLLAMA_URL)
                    .post(body)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.error("Ollama API 请求失败: {} - {}", response.code(), response.message());
                    String responseBody = response.body() != null ? response.body().string() : "No body";
                    logger.error("Ollama Response Body: {}", responseBody);
                    return null;
                }

                String responseBody = response.body().string();
                JsonNode root = mapper.readTree(responseBody);

                // Ollama 的响应结构
                if (root.has("response")) {
                    String translationText = root.get("response").asText();
                    if (translationText != null) {
                        // 【关键修改】：对 AI 模型的原始输出进行 trim()，去除首尾空白
                        return translationText.trim();
                    }
                    return null;
                } else {
                    logger.error("Ollama 响应中未找到 'response' 字段: {}", responseBody);
                    return null;
                }

            }
        } catch (IOException e) {
            logger.error("调用 Ollama API 时发生 IO 错误或 JSON 序列化错误", e);
            return null;
        }
    }


    /**
     * 保护特殊内容（如 XML 标签和变量），将其替换为占位符。
     * 【重点修改】：增强对复杂变量的保护。
     */
    private static String protectSpecialContent(String text, Map<String, String> replacements) {
        if (text == null) {
            return null;
        }

        String protectedText = text;

        // 重置计数器，确保每次翻译批次中的占位符唯一
        counter = 0;

        // 1. 保护 SimS 变量，例如 {0.SimName}, {M0.himself}
        // 使用一个更具体的类型 'COMPLEX_VAR' 来区分，确保优先处理。
        // protectedText = replace(protectedText, "\\{[^\\}]+\\}", "VAR", replacements);
        protectedText = replace(protectedText, "\\{[^\\}]+\\}", "COMPLEX_VAR", replacements);


        // 2. 保护 XML/HTML 标签，例如 <b>, <i>, <br/>, &lt;b&gt;
        // 匹配 <标签名> 或 </标签名> 或 <标签名 属性="值"> 或 <标签名/> (更宽松匹配)
        protectedText = replace(protectedText, "<\\/?\\w+(\\s+[^>]*?)?\\/?>", "TAG", replacements);

        // 3. 保护 XML 实体，例如 &amp;, &#x000D;
        // 增加 \s* 捕获可能尾随的空格
        protectedText = replace(protectedText, "&[a-zA-Z0-9#]+;\\s*", "ENTITY", replacements);

        // 4. 保护其他特殊字符串 (如果需要，可在此添加更多规则)
        // 例如：可能出现的特殊货币符号或未被保护的特殊符号
        // protectedText = replace(protectedText, "[€£¥$]", "SYMBOL", replacements);

        return protectedText;
    }

    /**
     * 恢复被保护的内容。
     */
    private static String restoreSpecialContent(String text, Map<String, String> replacements) {
        String restoredText = text;

        if (replacements == null || replacements.isEmpty()) {
            return restoredText;
        }

        // 必须反转键的顺序，以确保如果有嵌套或包含关系的占位符，
        // 先恢复最长的（在 replace 方法中，最长的通常是后匹配的，也就是 map 中靠后的 key，所以反转是正确的）
        List<String> keys = new ArrayList<>(replacements.keySet());
        Collections.reverse(keys);

        for (String placeholder : keys) {
            String original = replacements.get(placeholder);
            // 关键：使用 Matcher.quoteReplacement 来处理 original 字符串中的特殊字符，如 $ 和 \
            // 同时，我们也需要替换一些常见的LLM在占位符周围添加的字符，例如多余的空格
            String placeholderTrimmed = placeholder.trim(); // 占位符本身不包含空格，所以这一行实际没用

            // 【关键修改】：创建一个正则模式，用于匹配占位符前后可能出现的空白字符
            Pattern placeholderPattern = Pattern.compile("^[\\r\\n\\s]*" + Pattern.quote(placeholder) + "[\\r\\n\\s]*$", Pattern.DOTALL);

            // 1. 尝试使用正则匹配和替换，以处理 LLM 可能在占位符周围添加的换行或空格
            // 注意：这里需要替换的是整个文本，如果占位符是唯一的（如这里），使用 replaceAll 是安全的
            // 但因为 replaceAll 涉及到整个文本，为了安全和效率，我们继续使用 replace()

            // 1. 尝试替换 (最理想情况)
            // restoredText = restoredText.replace(placeholder, Matcher.quoteReplacement(original)); // 原始代码

            // 【新逻辑】：先处理最可能的情况：占位符前后带空格
            String withSpaceBefore = " " + placeholder;
            String withSpaceAfter = placeholder + " ";
            String withBothSpaces = " " + placeholder + " ";

            restoredText = restoredText.replace(withBothSpaces, Matcher.quoteReplacement(original));
            restoredText = restoredText.replace(withSpaceBefore, Matcher.quoteReplacement(original));
            restoredText = restoredText.replace(withSpaceAfter, Matcher.quoteReplacement(original));

            // 2. 尝试直接替换 (最后尝试精确替换，处理没有空格的情况)
            restoredText = restoredText.replace(placeholder, Matcher.quoteReplacement(original));
        }

        // 3. (可选但推荐) 最后清理：移除所有残余的占位符（避免翻译失败时它们留在最终译文中）
        // Pattern residualPattern = Pattern.compile(Pattern.quote(PREFIX) + ".*?" + Pattern.quote(SUFFIX));
        // restoredText = residualPattern.matcher(restoredText).replaceAll("");

        return restoredText;
    }
    /**
     * 正则替换工具方法
     */
    private static String replace(String text, String regex, String type, Map<String, String> replacements) {
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(text);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String original = matcher.group();
            // counter++ 确保每次生成的占位符都是唯一的
            String placeholder = PREFIX + type + "_" + (counter++) + SUFFIX;
            replacements.put(placeholder, original);
            // 关键：使用 Matcher.quoteReplacement 来处理原始文本中的特殊字符，避免在 appendReplacement 中被解释为捕获组引用
            matcher.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    // --- 变量格式检查工具方法 ---

    /**
     * 从文本中提取所有大括号变量，例如 {0.SimFirstName}。
     * @param text 文本
     * @return 变量集合
     */
    public static Set<String> extractVariables(String text) {
        Set<String> variables = new HashSet<>();
        if (text == null) {
            return variables;
        }

        // 用于匹配 {变量名}，例如 {0.SimFirstName}
        Pattern variablePattern = Pattern.compile("\\{[^\\}]+\\}");
        Matcher matcher = variablePattern.matcher(text);
        while (matcher.find()) {
            variables.add(matcher.group());
        }
        return variables;
    }

    /**
     * 检查译文是否丢失或损坏了原文中的大括号变量，以此判断是否需要重新翻译。
     *
     * 规则：如果 Source 包含变量，但 Dest 中变量集合与 Source 不完全一致，则判定为需要重译。
     *
     * @param sourceText 原始英文文本 (Source)
     * @param destText 现有中文译文 (Dest)
     * @return 如果原文包含变量但译文丢失或损坏了它们，则返回 true，表示需要重新翻译。
     */
    public static boolean needsRetranslationCheck(String sourceText, String destText) {
        if (sourceText == null || destText == null) {
            return false;
        }

        Set<String> sourceVariables = extractVariables(sourceText);

        // 1. 如果 Source 中没有变量，则格式正确，无需重译
        if (sourceVariables.isEmpty()) {
            return false;
        }

        Set<String> destVariables = extractVariables(destText);

        // 2. 如果 Source 有变量，但 Dest 没有，则重译
        if (destVariables.isEmpty()) {
            return true;
        }

        // 3. 检查变量集合是否完全一致
        // 检查 Dest 是否包含所有 Source 变量
        if (!destVariables.containsAll(sourceVariables)) {
            logger.warn("变量丢失：Source 包含 {}，Dest 缺少。",
                    sourceVariables.stream().filter(v -> !destVariables.contains(v)).collect(Collectors.joining(", ")));
            return true;
        }

        // 检查 Source 是否包含所有 Dest 变量 (防止多出不属于原文的变量)
        if (!sourceVariables.containsAll(destVariables)) {
            logger.warn("变量损坏/新增：Dest 包含 {}，Source 缺少。",
                    destVariables.stream().filter(v -> !sourceVariables.contains(v)).collect(Collectors.joining(", ")));
            return true;
        }


        // 如果变量集合完全一致，则格式正确，无需重译
        return false;
    }
}