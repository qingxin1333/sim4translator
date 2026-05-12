# Sim4Translator

一个基于Java的**AI驱动**XML文件翻译工具，利用人工智能技术进行批量翻译XML文件中的文本内容，并提供多种AI翻译服务选择。

## 项目简介

Sim4Translator是一个专门为XML文件设计的AI翻译工具，利用先进的人工智能翻译技术，主要应用于游戏本地化、文档翻译等场景。通过集成多个AI翻译平台，提供高质量、高效率的翻译服务。

## 功能特性

- 🤖 **AI智能翻译**: 集成多种AI翻译服务，提供高质量的翻译结果
- 🌐 **多翻译服务支持**: 支持Google翻译、百度翻译、有道翻译等AI翻译平台
- 📄 **XML文件处理**: 自动解析和翻译XML文件中的指定元素
- 🔄 **批量处理**: 支持批量处理多个XML文件
- 📝 **备份机制**: 自动备份原始文件，支持增量翻译
- 🎯 **智能过滤**: 自动识别已翻译内容，避免重复翻译
- 📊 **处理统计**: 提供详细的处理结果统计

## 项目结构

```
sim4translator/
├── fileIn/                 # 输入XML文件目录
├── fileOut/                # 输出翻译后文件目录
├── src/
│   └── main/
│       └── java/
│           └── com/
│               └── ty/
│                   ├── tran/
│                   │   ├── Main.java                    # 主程序入口
│                   │   ├── processor/
│                   │   │   ├── XmlTranslator.java       # XML翻译处理器
│                   │   │   └── FileBackupWriter.java    # 文件备份处理器
│                   │   └── translation/
│                   │       ├── GoogleTranslator.java    # Google翻译服务
│                   │       ├── BaiduTranslator.java     # 百度翻译服务
│                   │       └── YoudaoTranslator.java    # 有道翻译服务
│                   └── App.java                         # 应用程序类
├── pom.xml                 # Maven配置文件
└── README.md              # 项目说明文档
```

## 技术栈

- **Java 11**: 主要开发语言
- **Maven**: 项目构建和依赖管理
- **DOM4J**: XML文件解析和处理
- **Apache HttpClient**: HTTP客户端
- **Jackson**: JSON数据处理
- **Lombok**: 简化Java代码
- **SLF4J**: 日志框架

## 快速开始

### 环境要求

- Java 11 或更高版本
- Maven 3.6 或更高版本

### 安装和运行

1. **克隆项目**
   ```bash
   git clone <repository-url>
   cd sim4translator
   ```

2. **编译项目**
   ```bash
   mvn clean compile
   ```

3. **准备文件**
   - 将需要翻译的XML文件放入 `fileIn` 目录
   - 确保 `fileOut` 目录存在（程序会自动创建）

4. **运行程序**
   ```bash
   mvn exec:java -Dexec.mainClass="com.ty.tran.Main"
   ```

   或者直接运行编译后的JAR文件：
   ```bash
   java -cp target/classes com.ty.tran.Main
   ```

## 使用说明

### 输入文件格式

程序会处理 `fileIn` 目录中的XML文件，查找以下结构的元素：

```xml
<Content>
  <Table>
    <String>
      <Source>需要翻译的文本</Source>
      <Dest>翻译结果（可选）</Dest>
    </String>
  </Table>
</Content>
```

### 翻译流程

1. **文件扫描**: 扫描 `fileIn` 目录中的XML文件
2. **备份回写**: 将已翻译的内容回写到源文件
3. **批量翻译**: 对未翻译的内容进行批量处理
4. **结果输出**: 将翻译结果保存到 `fileOut` 目录

### 配置说明

翻译服务的相关配置可以在对应的翻译器类中进行修改：

- **批量大小**: 默认每批处理10条记录
- **延迟时间**: 默认批次间延迟100毫秒
- **中文检测**: 自动检测文本中是否包含中文字符

## 主要组件

### XmlTranslator
核心翻译处理器，负责：
- XML文件解析
- 翻译任务调度
- 批量处理控制

### FileBackupWriter
文件备份处理器，负责：
- 备份原始文件
- 回写已翻译内容
- 增量翻译支持

### AI翻译服务
- **GoogleTranslator**: Google AI翻译API集成，利用先进的神经网络翻译技术
- **BaiduTranslator**: 百度AI翻译API集成，基于深度学习的翻译服务  
- **YoudaoTranslator**: 有道AI翻译API集成，采用人工智能翻译引擎

## 注意事项

- 程序会自动跳过已备份的文件（文件名包含时间戳格式）
- 翻译失败会记录错误日志，但不会中断整个处理流程
- 建议在处理大量文件时适当调整批处理大小和延迟时间

## 日志输出

程序使用SLF4J进行日志记录，包含：
- 处理进度信息
- 错误和异常信息
- 最终统计结果

## 许可证

本项目采用 MIT 许可证。

## 贡献

欢迎提交Issue和Pull Request来改进这个项目。

## 联系方式

如有问题或建议，请通过GitHub Issues联系。
