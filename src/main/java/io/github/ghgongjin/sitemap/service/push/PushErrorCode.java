package io.github.ghgongjin.sitemap.service.push;

/**
 * @ClassName PushErrorCode
 * @Description 推送错误分类码（写入推送日志，用于界面提示与排查）
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
public enum PushErrorCode {
    /** 无法建立连接（拒绝、DNS、网络不可达） */
    CONNECT_FAILED,
    /** 连接或读写超时 */
    TIMEOUT,
    /** 认证失败（账号、密码或私钥错误） */
    AUTH_FAILED,
    /** SFTP 主机密钥指纹与已记录的不一致 */
    HOST_KEY_MISMATCH,
    /** 远端目录不存在或不可进入 */
    DIR_NOT_FOUND,
    /** 远端目录无写权限 */
    PERMISSION_DENIED,
    /** 上传或替换文件失败 */
    UPLOAD_FAILED,
    /** IndexNow 提交失败 */
    INDEXNOW_FAILED
}
