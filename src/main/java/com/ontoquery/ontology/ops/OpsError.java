package com.ontoquery.ontology.ops;

/**
 * 本体运维错误码（5 位：A=用户端，B=系统端）。
 *
 * @author 月夜烛峰
 */
public final class OpsError {

    public static final String BAD_PARAM = "A0401";
    public static final String PROTECTED = "A0403";
    public static final String NOT_FOUND = "A0404";
    public static final String DUPLICATE = "A0409";
    public static final String NOTHING_TO_REVERT = "A0410";
    public static final String VALUE_EXPR = "B0501";
    public static final String RELOAD_FAILED = "B0502";

    private OpsError() {
    }
}
