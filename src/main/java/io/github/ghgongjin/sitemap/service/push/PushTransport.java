package io.github.ghgongjin.sitemap.service.push;

/**
 * @ClassName PushTransport
 * @Description 推送传输抽象：连接测试与文件上传（覆盖写）
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
public interface PushTransport {

    PushProtocol protocol();

    /**
     * 连接、认证并检查远端目录是否可用
     *
     * @return 连接过程中观察到的 SFTP 主机密钥指纹（非 SFTP 返回 null），
     *         供调用方在首次连接（TOFU）时记录
     */
    String verify(PushTarget target) throws PushTransportException;

    /**
     * 上传内容并覆盖远端同名文件（先写临时文件再改名，不支持改名时回退直接覆盖）
     *
     * @return 观察到的 SFTP 主机密钥指纹（非 SFTP 返回 null）
     */
    String upload(PushTarget target, String remoteFileName, byte[] content) throws PushTransportException;
}
