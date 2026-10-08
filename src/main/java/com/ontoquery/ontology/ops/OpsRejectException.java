package com.ontoquery.ontology.ops;

/**
 * 运维操作拒绝异常：携带 5 位错误码，由 GlobalExceptionHandler 统一转译。
 *
 * @author 月夜烛峰
 */
public class OpsRejectException extends RuntimeException {

    private final String code;

    public OpsRejectException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
