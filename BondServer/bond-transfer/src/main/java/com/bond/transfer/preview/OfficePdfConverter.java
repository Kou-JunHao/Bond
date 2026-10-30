package com.bond.transfer.preview;

import java.io.InputStream;
import java.time.Duration;

/**
 * Office 文档 → PDF 转换器抽象（task-59）。
 *
 * <p>生产实现为 {@link GotenbergPdfConverter}（Gotenberg 8 的 LibreOffice 路由）；
 * 单测注入假实现，不依赖外网/容器。</p>
 */
public interface OfficePdfConverter {

    /**
     * 转换一个 Office 文档为 PDF。
     *
     * @param endpoint   转换服务基地址（如 {@code http://bond-gotenberg:3000}）
     * @param timeout    单次转换超时（含传输与转换时间）
     * @param fileName   源文件名（用于 multipart filename，扩展名决定转换器选择）
     * @param source     源文件字节流（调用方负责关闭）
     * @param sourceSize 源文件字节数
     * @param formFields 额外 multipart 表单参数（如 xlsx 的 {@code singlePageSheets=true}），可为空
     * @return PDF 字节流（调用方负责关闭，且必须在返回流关闭后才可释放底层连接）
     * @throws ConversionUnavailableException 转换服务不可用/超时/5xx
     * @throws BadDocumentException           文档无法转换（4xx，坏文档/不支持）
     */
    InputStream convert(String endpoint, Duration timeout, String fileName, InputStream source, long sourceSize,
                        java.util.Map<String, String> formFields);

    /** 兼容旧调用：不带额外表单参数。 */
    default InputStream convert(String endpoint, Duration timeout, String fileName, InputStream source, long sourceSize) {
        return convert(endpoint, timeout, fileName, source, sourceSize, java.util.Map.of());
    }

    /** 转换服务不可用（连接失败/超时/5xx）→ 对外 503。 */
    class ConversionUnavailableException extends RuntimeException {
        public ConversionUnavailableException(String message) {
            super(message);
        }

        public ConversionUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 文档无法转换（源文件损坏/格式不支持）→ 对外 415，并进入负缓存。 */
    class BadDocumentException extends RuntimeException {
        public BadDocumentException(String message) {
            super(message);
        }

        public BadDocumentException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
