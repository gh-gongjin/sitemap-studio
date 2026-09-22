package io.github.ghgongjin.sitemap.service.submission;

/**
 * 搜索引擎提交错误分类（写入 submission_log.error_code，用于界面提示与排查）
 */
public enum SubmissionErrorCode {
    /** 到百度的网络/HTTP 传输失败 */
    BAIDU_TRANSPORT,
    /** 百度拒绝提交（业务错误码或响应不可解析） */
    BAIDU_REJECTED,
    /** GSC 授权端点拒绝 JWT 断言（服务账号 JSON 问题） */
    GSC_TOKEN_REJECTED,
    /** GSC API 返回 401（access token 无效或凭据/授权已失效） */
    GSC_UNAUTHORIZED,
    /** GSC 返回 403（服务账号未加入站点用户） */
    GSC_NOT_A_SITE_USER,
    /** GSC API 其他非 2xx */
    GSC_API_REJECTED,
    /** 到 Google 的网络不可达 */
    GSC_NETWORK,
    /** 配置缺失/非法（token 未配置、存量 JSON 损坏等） */
    CONFIG_INVALID,
    /** 凭据解密失败（密钥轮换后） */
    CONFIG_DECRYPT_FAILED
}
