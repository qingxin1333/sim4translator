// XmlTranslator.java - 修正后的版本 (已增加批量处理和延迟)
package com.ty.tran.processor;

import com.ty.tran.translation.GoogleTranslator;
import lombok.extern.slf4j.Slf4j;
import org.dom4j.*;
import org.dom4j.io.OutputFormat;
import org.dom4j.io.SAXReader;
import org.dom4j.io.XMLWriter;
import org.xml.sax.SAXException;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
public class XmlTranslator {

    // 固定的输入输出目录
    private static final String INPUT_DIR = "fileIn";
    private static final String OUTPUT_DIR = "fileOut";

    // XML 文件中的元素路径
    private static final String TABLE_XPATH = "//Content/Table";
    private static final String STRING_XPATH = "String"; // 相对于 Table 元素
    private static final String SOURCE_TAG = "Source";
    private static final String DEST_TAG = "Dest";

    private static int batchSize = 10; // 每批次 10 条
    private static long delayMs = 100;  // 延迟 100 毫秒

    // 用于检查文本中是否包含中文
    private static final Pattern CHINESE_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]");

    // 用于保存需要翻译的条目及其在 XML 中的位置信息
    private static class TranslationItem {
        public String id;
        public Element sourceElement;
        public Element destElement;
        public String sourceText;
        public String existingTranslation; // 存储现有的翻译内容
        public Element tableElement; // 所属的Table元素

        public TranslationItem(String id, Element sourceElement, Element destElement,
                               String sourceText, String existingTranslation, Element tableElement) {
            this.id = id;
            this.sourceElement = sourceElement;
            this.destElement = destElement;
            this.sourceText = sourceText;
            this.existingTranslation = existingTranslation;
            this.tableElement = tableElement;
        }
    }

    private final GoogleTranslator googleTranslator = new GoogleTranslator();

    /**
     * 执行翻译流程：读取文件、筛选内容、翻译、写回文件。
     */
    public void translateFiles() {
        File inputDir = new File(INPUT_DIR);
        File[] xmlFiles = inputDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".xml"));

        if (xmlFiles == null || xmlFiles.length == 0) {
            log.info("输入目录中未找到任何 XML 文件。");
            return;
        }

        for (File inputFile : xmlFiles) {
            try {
                processFile(inputFile);
            } catch (Exception e) {
                log.error("处理文件 {} 时发生错误", inputFile.getName(), e);
            }
        }
        log.info("所有文件翻译处理完成。");
    }

    /**
     * 处理单个 XML 文件。
     *
     * @param inputFilePath 输入文件的绝对路径
     * @param outputDirPath 输出目录路径
     * @throws Exception 处理过程中可能抛出的异常
     */
    public void translateXmlFile(String inputFilePath, String outputDirPath) throws Exception {
        File inputFile = new File(inputFilePath);
        processFile(inputFile);
    }
// 新增方法：清理翻译结果，解决用户提出的所有三个问题
    /**
     * 清理翻译结果中的额外内容、分隔符和多余换行符。
     * 解决问题：
     * 1. 译文带有 "|||" 符号 (批处理分隔符未正确处理)
     * 2. 译文包含后续句子翻译的内容 (批处理结果分配错误)
     * 3. 译文中有部分换行符变多 (AI 模型输出或格式化问题)
     *
     * @param text 原始译文
     * @return 清理后的译文
     */
    private String cleanTranslationResult(String text) {
        if (text == null) {
            return "";
        }
        String cleanedText = text;

        // 修复 1 & 2: 截断多余的翻译内容（通常由批处理响应解析错误导致）
        // 如果译文包含 "|||" 分隔符，我们只取分隔符之前的内容，因为它通常是当前句子的正确译文。
        int separatorIndex = cleanedText.indexOf("|||");
        if (separatorIndex != -1) {
            // 记录警告，可以根据实际情况打印翻译的 ID
            log.warn("翻译结果中检测到 '|||' 分隔符，已截断多余内容。");
            cleanedText = cleanedText.substring(0, separatorIndex);
        }

        // 修复 3: 清理多余的换行符。将两个或更多连续的换行符替换为单个换行符。
        // 使用正则表达式消除连续的空白行 (包括 \r\n 和 \n)
        cleanedText = cleanedText.replaceAll("(?:\r?\n){2,}", "\n");

        // 最后，去除首尾的空白（包括换行），确保译文内容干净
        return cleanedText.trim();
    }
    /**
     * 处理单个 XML 文件。
     */
    private void processFile(File inputFile) throws DocumentException, IOException, SAXException {
        String inputPath = inputFile.getAbsolutePath();
        String outputPath = Paths.get(OUTPUT_DIR, inputFile.getName()).toString();

        log.info("--- 开始处理文件: {} ---", inputFile.getName());

        // 1. 复制文件到输出目录 (作为工作副本)
        copyFile(inputPath, outputPath);
        Document document = readDocument(outputPath);

        // 2. 收集需要翻译的条目
        List<TranslationItem> translationItems = collectTranslationItems(document);
        long totalCount = translationItems.size();
        long needTranslateCount = translationItems.stream().filter(item ->
                needsTranslation(item.existingTranslation, item.sourceText, item.id)
        ).count();

        log.info("文件 '{}' 共 {} 条目，其中 {} 条需要翻译或重译。", inputFile.getName(), totalCount, needTranslateCount);

        if (needTranslateCount == 0) {
            log.info("文件 '{}' 无需翻译，跳过。", inputFile.getName());
            return;
        }

        // 3. 提取待翻译的源文本并分类
        List<TranslationItem> itemsToTranslate = translationItems.stream()
                .filter(item -> needsTranslation(item.existingTranslation, item.sourceText, item.id))
                .collect(Collectors.toList());

        // 【修改点 3】：将待翻译条目分为普通批次、复杂批次和超长单独批次
        List<TranslationItem> longTextItems = itemsToTranslate.stream()
                .filter(item -> item.sourceText.length() >= 300) // 超长文本：300字符以上 (可根据需要调整)
                .collect(Collectors.toList());

        // 过滤掉超长文本后，再处理普通/复杂分类
        List<TranslationItem> remainingItems = itemsToTranslate.stream()
                .filter(item -> item.sourceText.length() < 300)
                .collect(Collectors.toList());

        // 重新定义普通批次和复杂批次
        List<TranslationItem> normalItems = remainingItems.stream()
                .filter(item -> !isComplexText(item.sourceText))
                .collect(Collectors.toList());

        List<TranslationItem> complexItems = remainingItems.stream()
                .filter(item -> isComplexText(item.sourceText))
                .collect(Collectors.toList());

        log.info("普通批次条目数: {}，复杂批次条目数: {}，超长文本条目数: {}", normalItems.size(), complexItems.size(), longTextItems.size());


        // 用于存储所有翻译结果，顺序与 itemsToTranslate 保持一致（重要）
        Map<TranslationItem, String> translatedMap = new LinkedHashMap<>();


        // 4.1. 翻译普通批次 (大批次，低延迟)
        int normalBatchSize = 15; // 提高普通句子的批次大小
        long normalDelayMs = 50;  // 减少普通句子的延迟
        translateAndStore(normalItems, normalBatchSize, normalDelayMs, translatedMap, "普通批次");

        // 4.2. 翻译复杂批次 (中批次，中延迟)
        int complexBatchSize = 5; // 复杂文本使用更小的批次
        long complexDelayMs = 200; // 复杂文本使用更长的延迟
        translateAndStore(complexItems, complexBatchSize, complexDelayMs, translatedMap, "复杂批次");

        // 4.3. 翻译超长文本 (单批次，高延迟)
        int longTextBatchSize = 1; // 每一个超长文本单独一批
        long longTextDelayMs = 500; // 更长的延迟
        translateAndStore(longTextItems, longTextBatchSize, longTextDelayMs, translatedMap, "超长文本批次");


        // 5. 将翻译结果写回 XML 文档
        int updatedCount = 0;

// 遍历所有需要翻译的条目，检查它们在 translatedMap 中是否有结果
        for (TranslationItem item : itemsToTranslate) {
            if (!translatedMap.containsKey(item)) {
                log.warn("ID={} 的翻译未在 translatedMap 中找到结果，跳过回写。", item.id);
                continue;
            }

            String newTranslation = translatedMap.get(item);

            // 【关键修改】：调用清理方法
            String finalTranslation = cleanTranslationResult(newTranslation);

            // 确保翻译结果不为 null 或空
            if (finalTranslation != null && !finalTranslation.trim().isEmpty()) {
                // 只有当新的翻译内容不同于现有的翻译内容时才更新
                if (!finalTranslation.equals(item.existingTranslation)) {
                    // 额外的清理逻辑（如果 Source 没有换行，且 Dest 前后有多余换行，则 trim）
                    // 这一步在 cleanTranslationResult 中已经处理了，但可以作为额外的保险
                    if (!item.sourceText.contains("\n") && finalTranslation.matches("^[\\r\\n]+.*[\\r\\n]+$")) {
                        finalTranslation = finalTranslation.trim(); // 确保最终内容干净
                    }

                    item.destElement.setText(finalTranslation);
                    updatedCount++;
                    log.debug("更新翻译: ID={}", item.id);
                }
            } else {
                log.warn("ID={} 的翻译失败，结果为空。保留原文。", item.id);
            }
        }

        // 6. 写入修改后的 XML 文档
        if (updatedCount > 0) {
            writeDocument(document, outputPath);
            log.info("文件 '{}' 更新了 {} 条翻译，已保存到输出目录。", inputFile.getName(), updatedCount);
        } else {
            log.info("文件 '{}' 所有需要翻译的条目均已存在，无需更新文件。", inputFile.getName());
        }
    }
    /**
     * 辅助方法：执行批量翻译并存储结果
     */
    private void translateAndStore(
            List<TranslationItem> items,
            int currentBatchSize,
            long currentDelayMs,
            Map<TranslationItem, String> translatedMap,
            String batchName) {

        if (items.isEmpty()) return;

        List<String> sourceTexts = items.stream().map(item -> item.sourceText).collect(Collectors.toList());
        List<String> translatedTexts = new ArrayList<>();
        int totalItems = sourceTexts.size();

        log.info("开始翻译 {} ({}) 条目...", totalItems, batchName);

        for (int i = 0; i < totalItems; i += currentBatchSize) {
            int endIndex = Math.min(i + currentBatchSize, totalItems);
            List<String> currentBatch = sourceTexts.subList(i, endIndex);

            // 调用翻译API
            List<String> batchResult = googleTranslator.translateBatch(currentBatch);
            translatedTexts.addAll(batchResult);

            log.info("{} 已完成 {}/{} 条目翻译...", batchName, translatedTexts.size(), totalItems);

            // 线程休息
            if (endIndex < totalItems) {
                try {
                    Thread.sleep(currentDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("翻译线程被中断。", e);
                }
            }
        }

        // 将翻译结果映射回 TranslationItem
        for (int i = 0; i < items.size(); i++) {
            if (i < translatedTexts.size()) {
                translatedMap.put(items.get(i), translatedTexts.get(i));
            } else {
                log.warn("翻译结果列表长度不足，ID={} 的翻译跳过映射。", items.get(i).id);
            }
        }
        log.info("{} 翻译完成。", batchName);
    }
    /**
     * 收集所有需要翻译的 String 节点。
     */
    private List<TranslationItem> collectTranslationItems(Document document) {
        List<TranslationItem> items = new ArrayList<>();
        // 查找所有 Table 元素
        List<Node> tableNodes = document.selectNodes(TABLE_XPATH);
        List<Element> tableElements = new ArrayList<>();
        for (Node node : tableNodes) {
            if (node instanceof Element) {
                tableElements.add((Element) node);
            }
        }

        for (Element tableElement : tableElements) {
            // 查找 Table 下的所有 String 元素
            List<Element> stringElements = tableElement.elements(STRING_XPATH);

            for (Element stringElement : stringElements) {
                String id = stringElement.attributeValue("id");
                Element sourceElement = stringElement.element(SOURCE_TAG);
                Element destElement = stringElement.element(DEST_TAG);

                if (sourceElement != null && destElement != null) {
                    String sourceText = sourceElement.getStringValue();
                    String destText = destElement.getStringValue();

                    items.add(new TranslationItem(id, sourceElement, destElement, sourceText, destText, tableElement));
                }
            }
        }
        return items;
    }


    /**
     * 替换后的 needsTranslation 方法，接受 id 参数并增加格式检查
     * * @param destText 目标文本（Dest）
     * @param sourceText 源文本（Source）
     * @param id String 元素的 ID
     * @return 如果需要翻译，返回 true
     */
    private boolean needsTranslation(String destText, String sourceText, String id) {
        // 1. 如果目标文本为空或与源文本相同，需要翻译
        if (destText == null || destText.trim().isEmpty() || destText.equals(sourceText)) {
            return true;
        }

        boolean destIsChinese = containsChinese(destText);

        // 2. 标准已翻译判断 Complete name : F:\TDownload\av\finaly\Adult Video\TRE\TRE-084\THZU.CCTRE-084CD1.mp4
        // 如果目标文本不是中文，说明可能未翻译或翻译失败，需要翻译
        if (!destIsChinese) {
            return true;
        }

        // --- 新增重译过滤逻辑：如果标准判断认为已翻译，则检查变量格式 ---

        // 使用 GoogleTranslator 提供的格式检查方法
        if (GoogleTranslator.needsRetranslationCheck(sourceText, destText)) {
            // 记录日志，输出导致重译的具体条目
            log.warn("强制重译 (变量格式错误): ID={}。 Source: '{}...' Dest: '{}...' (保留 {} 个变量，预期 {})",
                    id,
                    sourceText.length() > 50 ? sourceText.substring(0, 50) + "..." : sourceText,
                    destText.length() > 50 ? destText.substring(0, 50) + "..." : destText,
                    GoogleTranslator.extractVariables(destText).size(),
                    GoogleTranslator.extractVariables(sourceText).size()
            );
            return true; // 变量格式错误，强制重译
        }

        // 满足所有条件，认为已翻译且格式正确
        return false;
    }

    /**
     * 检查文本中是否包含中文
     */
    private boolean containsChinese(String text) {
        if (text == null) {
            return false;
        }
        return CHINESE_PATTERN.matcher(text).find();
    }

    /**
     * 读取 XML 文档
     */
    private Document readDocument(String filePath) throws DocumentException {
        SAXReader reader = new SAXReader();
        try {
            reader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (Exception ignore) {
        }
        // 【关键修改 1】：不去除空白文本节点，以保留元素内部的换行符
        reader.setStripWhitespaceText(false);

        return reader.read(new File(filePath));
    }

    /**
     * 判断文本是否属于复杂类型（例如，包含特定数量或类型的变量）
     */
    private boolean isComplexText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }

        // --- 策略 1: 变量数量 (保持原有策略) ---
        // 示例：包含两个或更多大括号变量的文本视为复杂文本
        if (GoogleTranslator.extractVariables(text).size() >= 2) {
            return true;
        }

        // --- 策略 2: 文本长度 (新增策略) ---
        // 将长度超过 150 个字符的文本视为复杂文本，单独处理
        final int LONG_TEXT_THRESHOLD = 150;
        if (text.length() >= LONG_TEXT_THRESHOLD) {
            return true;
        }

        return false;
    }

    /**
     * 写入 XML 文档
     */
    private void writeDocument(Document document, String filePath) throws IOException {
        // 【关键修改】：将 OutputFormat 从 createPrettyPrint() 调整为 createCompactFormat()。
        // CompactFormat 相比 PrettyPrint 更不容易在元素内容中引入/去除不必要的空白和换行，
        // 从而最大限度地保留翻译内容的原始格式（包括内部换行）。
        OutputFormat format = OutputFormat.createCompactFormat();
        format.setEncoding("UTF-8");
        // 必须保留 setTrimText(false)，以确保 XMLWriter 不会去除 <Dest> 标签内容中的换行和空格。
        format.setTrimText(false);

        XMLWriter writer = null;
        try (FileOutputStream fos = new FileOutputStream(filePath)) {
            writer = new XMLWriter(fos, format);
            writer.write(document);
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    /**
     * 文件拷贝工具
     */
    private void copyFile(String sourcePath, String destPath) throws IOException {
        Files.copy(Paths.get(sourcePath), Paths.get(destPath), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}