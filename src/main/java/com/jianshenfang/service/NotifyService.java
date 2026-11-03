package com.jianshenfang.service;

/**
 * 通知渠道抽象。第一期实现为 MockNotifyService（仅落库记录），
 * 后续接入公众号模板消息时新增实现即可，业务代码无需改动。
 */
public interface NotifyService {

    /**
     * @param scene    场景：BOOKING_CREATED / BOOKING_CANCELED / AUTO_CHECKIN / PACKAGE_ABNORMAL
     * @param receiver 接收方手机号
     * @param bizId    关联业务 id（预约 id 等）
     * @param content  通知内容
     */
    void send(String scene, String receiver, Long bizId, String content);
}
