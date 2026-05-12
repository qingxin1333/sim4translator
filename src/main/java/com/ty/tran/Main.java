// Main.java
package com.ty.tran;

import com.ty.tran.processor.XmlTranslator;
import com.ty.tran.processor.FileBackupWriter;
import lombok.extern.slf4j.Slf4j;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
public class Main {

    // 固定的输入输出目录
    private static final String INPUT_DIR = "fileIn";
    private static final String OUTPUT_DIR = "fileOut";

    public static void main(String[] args) {
        // 检查输入目录是否存在
        File inputDir = new File(INPUT_DIR);
        if (!inputDir.exists() || !inputDir.isDirectory()) {
            log.error("输入目录不存在: {}", INPUT_DIR);
            log.info("错误: 输入目录 " + INPUT_DIR + " 不存在");
            System.exit(1);
        }

        // 创建输出目录（如果不存在）
        File outputDir = new File(OUTPUT_DIR);
        if (!outputDir.exists()) {
            if (outputDir.mkdirs()) {
                log.info("创建输出目录: {}", OUTPUT_DIR);
            } else {
                log.error("无法创建输出目录: {}", OUTPUT_DIR);
                log.info("错误: 无法创建输出目录");
                System.exit(1);
            }
        }

        // *** 关键修改：不再清理输出目录，而是先回写已翻译的内容 ***
        log.info("开始回写已翻译的内容到源文件...");
        backupTranslatedFiles();

        // 修改文件过滤逻辑，排除备份文件（文件名包含时间戳格式的文件）
        File[] xmlFiles = inputDir.listFiles((dir, name) -> {
            if (!name.toLowerCase().endsWith(".xml")) {
                return false; // 不是XML文件直接排除
            }
            // 排除备份文件（匹配格式：原始文件名_YYYYMMDDHHMMSS.xml）
            String baseName = name.substring(0, name.lastIndexOf('.'));
            return !baseName.matches(".+_[0-9]{14}$"); // 不匹配时间戳格式的文件才处理
        });

        if (xmlFiles == null || xmlFiles.length == 0) {
            log.warn("输入目录中没有找到XML文件: {}", INPUT_DIR);
            log.info("警告: 输入目录中没有找到XML文件");
            return;
        }

        XmlTranslator translator = null;

        // *** 关键修改：添加失败统计 ***
        int successCount = 0;
        int failedCount = 0;
        int totalCount = xmlFiles.length;

        try {
            // *** 关键修改：调用无参数构造函数 ***
            translator = new XmlTranslator();

            for (File xmlFile : xmlFiles) {
                try {
                    log.info("开始处理文件: {}", xmlFile.getName());
                    log.info("正在处理: " + xmlFile.getName());

                    // *** 关键修改：使用新的translateXmlFile方法 ***
                    translator.translateXmlFile(xmlFile.getAbsolutePath(), OUTPUT_DIR);
                    successCount++;

                    log.info("文件处理完成: {}", xmlFile.getName());
                    // 避免打印两次"完成"
                    // log.info("完成: " + xmlFile.getName());

                } catch (Exception e) {
                    // *** 关键修改：捕获异常并统计失败次数 ***
                    failedCount++;
                    log.error("处理文件失败: {}", xmlFile.getName(), e);
                    log.info("错误: 处理文件失败 - " + xmlFile.getName() + ". 详细信息请查看日志。");
                }
            }

            // *** 关键修改：显示包含失败统计的完整结果 ***
            log.info("翻译处理完成。成功: {}/{}，失败: {}", successCount, totalCount, failedCount);
            log.info("翻译处理完成。成功处理: " + successCount + "/" + totalCount + " 个文件，失败: " + failedCount + " 个");

        } catch (Exception e) {
            log.error("翻译过程失败", e);
            log.info("错误: 翻译过程失败。");
        }
    }

    /**
     * 回写已翻译的内容到源文件
     */
    private static void backupTranslatedFiles() {
        try {
            FileBackupWriter backupWriter = new FileBackupWriter();
            backupWriter.backupTranslatedFiles();
            log.info("回写翻译内容完成");
        } catch (Exception e) {
            log.error("回写翻译内容时发生错误", e);
            log.info("警告: 回写翻译内容失败，将继续进行翻译流程");
        }
    }

    /**
     * .
     * 清理输出目录中的XML文件（保留但不再自动调用）
     */
    private static void cleanupOutputDirectory(String outputDirPath) {
        try {
            File outputDir = new File(outputDirPath);
            File[] existingFiles = outputDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".xml"));

            if (existingFiles != null && existingFiles.length > 0) {
                int deletedCount = 0;
                for (File file : existingFiles) {
                    if (file.delete()) {
                        deletedCount++;
                        log.debug("已删除旧文件: {}", file.getName());
                    } else {
                        log.warn("无法删除文件: {}", file.getName());
                    }
                }
                log.info("清理完成，删除了 {} 个旧XML文件", deletedCount);
            } else {
                log.info("输出目录中没有需要清理的XML文件");
            }
        } catch (Exception e) {
            log.error("清理输出目录时发生错误", e);
            log.info("警告: 清理输出目录失败，可能会影响新文件的生成");
        }
    }
}