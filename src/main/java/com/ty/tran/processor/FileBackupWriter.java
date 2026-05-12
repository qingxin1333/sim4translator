package com.ty.tran.processor;
// FileBackupWriter.java
import lombok.extern.slf4j.Slf4j;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.OutputFormat;
import org.dom4j.io.SAXReader;
import org.dom4j.io.XMLWriter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class FileBackupWriter {

    private static final String INPUT_DIR = "fileIn";
    private static final String OUTPUT_DIR = "fileOut";

    public static void main(String[] args) {
        new FileBackupWriter().backupTranslatedFiles();
    }

    /**
     * 将翻译后的文件从输出目录回写到输入目录
     */
    public void backupTranslatedFiles() {
        File outputDir = new File(OUTPUT_DIR);
        if (!outputDir.exists() || !outputDir.isDirectory()) {
            log.error("输出目录不存在: {}", OUTPUT_DIR);
            return;
        }

        File inputDir = new File(INPUT_DIR);
        if (!inputDir.exists() || !inputDir.isDirectory()) {
            log.error("输入目录不存在: {}", INPUT_DIR);
            return;
        }

        // 获取输出目录中的所有XML文件
        File[] translatedFiles = outputDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".xml"));

        if (translatedFiles == null || translatedFiles.length == 0) {
            log.warn("输出目录中没有找到XML文件: {}", OUTPUT_DIR);
            return;
        }

        int successCount = 0;
        int totalCount = translatedFiles.length;

        log.info("开始回写翻译文件，共找到 {} 个文件", totalCount);

        for (File translatedFile : translatedFiles) {
            try {
                String fileName = translatedFile.getName();
                File sourceFile = new File(inputDir, fileName);

                if (!sourceFile.exists()) {
                    log.warn("输入目录中不存在对应的源文件: {}", fileName);
                    continue;
                }

                // 注释掉备份源文件的代码，不再创建备份文件
                // backupOriginalFile(sourceFile);

                // 合并翻译内容到源文件
                boolean success = mergeTranslatedContent(translatedFile, sourceFile);

                if (success) {
                    successCount++;
                    log.info("成功回写文件: {}", fileName);
                } else {
                    log.error("回写文件失败: {}", fileName);
                }

            } catch (Exception e) {
                log.error("处理文件失败: {}", translatedFile.getName(), e);
            }
        }

        log.info("回写完成。成功: {}/{}", successCount, totalCount);
    }

    /**
     * 备份原始文件
     */
    private void backupOriginalFile(File sourceFile) throws IOException {
        String fileName = sourceFile.getName();
        String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
        String extension = fileName.substring(fileName.lastIndexOf('.'));
        String timestamp = new java.text.SimpleDateFormat("yyyyMMddHHmmss").format(new java.util.Date());
        String backupFileName = baseName + "_" + timestamp + extension;
        Path backupPath = Paths.get(INPUT_DIR, backupFileName);

        Files.copy(sourceFile.toPath(), backupPath, StandardCopyOption.REPLACE_EXISTING);
        log.debug("已备份原始文件: {}", backupFileName);
    }


    /**
     * 合并翻译内容到源文件
     */
    private boolean mergeTranslatedContent(File translatedFile, File sourceFile) {
        try {
            // 读取翻译后的文件
            Document translatedDoc = readDocument(translatedFile.getAbsolutePath());
            Element translatedRoot = translatedDoc.getRootElement();

            // 读取源文件
            Document sourceDoc = readDocument(sourceFile.getAbsolutePath());
            Element sourceRoot = sourceDoc.getRootElement();

            // 收集翻译后的内容（ID -> 翻译文本映射）
            Map<String, String> translationMap = collectTranslations(translatedRoot);

            // 更新源文件中的翻译内容
            boolean updated = updateSourceWithTranslations(sourceRoot, translationMap);

            if (updated) {
                // 写回源文件
                writeDocument(sourceDoc, sourceFile.getAbsolutePath());
                return true;
            } else {
                log.warn("文件没有需要更新的内容: {}", sourceFile.getName());
                return false;
            }

        } catch (Exception e) {
            log.error("合并翻译内容失败: {}", sourceFile.getName(), e);
            return false;
        }
    }

    /**
     * 从翻译后的文档中收集所有翻译内容
     */
    private Map<String, String> collectTranslations(Element rootElement) {
        Map<String, String> translationMap = new HashMap<>();
        Element contentElement = rootElement.element("Content");

        if (contentElement != null) {
            for (Element table : contentElement.elements("Table")) {
                for (Element stringElement : table.elements("String")) {
                    String id = stringElement.attributeValue("id");
                    Element destElement = stringElement.element("Dest");

                    if (id != null && destElement != null) {
                        String translatedText = destElement.getStringValue();
                        translationMap.put(id, translatedText);
                    }
                }
            }
        }

        log.debug("收集到 {} 个翻译条目", translationMap.size());
        return translationMap;
    }

    /**
     * 使用翻译内容更新源文档
     */
    private boolean updateSourceWithTranslations(Element sourceRoot, Map<String, String> translationMap) {
        boolean updated = false;
        Element contentElement = sourceRoot.element("Content");

        if (contentElement != null) {
            for (Element table : contentElement.elements("Table")) {
                for (Element stringElement : table.elements("String")) {
                    String id = stringElement.attributeValue("id");

                    if (id != null && translationMap.containsKey(id)) {
                        Element destElement = stringElement.element("Dest");
                        if (destElement != null) {
                            String newTranslation = translationMap.get(id);
                            String currentTranslation = destElement.getStringValue();

                            // 只有当翻译内容不同时才更新
                            if (!newTranslation.equals(currentTranslation)) {
                                destElement.setText(newTranslation);
                                updated = true;
                                log.debug("更新翻译: ID={}", id);
                            }
                        }
                    }
                }
            }
        }

        return updated;
    }

    /**
     * 读取XML文档
     */
    /**
     * 读取XML文档
     */
    private Document readDocument(String filePath) throws DocumentException {
        SAXReader reader = new SAXReader();
        // 设置 SAXReader 属性以忽略 DTD 验证
        try {
            reader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (Exception ignore) {
            // 忽略设置失败，继续
            log.error("设置 DTD 验证失败，忽略设置失败，继续");
        }
        // 【关键修改】：设置 XMLReader 保留空白节点，从而保留元素内容中的换行
        reader.setIncludeInternalDTDDeclarations(true); // 可选：保留 DTD 声明
        reader.setStripWhitespaceText(false); // 【新增】不去除空白文本节点

        return reader.read(new File(filePath));
    }

    /**
     * 写入XML文档
     */
    private void writeDocument(Document document, String outputPath) throws IOException {
        OutputFormat format = OutputFormat.createPrettyPrint();
        format.setEncoding("UTF-8");
        format.setIndent(true);
        format.setIndent("  ");
        format.setNewlines(true);

        try (FileOutputStream fos = new FileOutputStream(outputPath)) {
            XMLWriter writer = new XMLWriter(fos, format);
            writer.write(document);
            writer.close();
        }
    }
}