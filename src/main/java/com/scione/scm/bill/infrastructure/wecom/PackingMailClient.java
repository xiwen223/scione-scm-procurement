package com.scione.scm.bill.infrastructure.wecom;

import com.scione.scm.bill.config.SopReminderProperties;
import com.scione.scm.bill.infrastructure.wecom.WeComWebhookClient.SendOutcome;
import com.scione.scm.bill.infrastructure.wecom.enums.SendState;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Properties;

/** SMTP只发给一个明确邮箱；连接与发送分开，结果未知不自动重发。 */
@Component
@RequiredArgsConstructor
public class PackingMailClient {
    public static final String SUBJECT = "关联货件催办";//邮件主题固定前缀
    private final SopReminderProperties properties;//读Nacos里的SMTP配置

    /** 校验邮箱地址是否合法：非空、无换行、能被解析、且带@。 */
    public static boolean validAddress(String value) {
        if (value == null || value.isBlank() || value.contains("\r") || value.contains("\n")) return false;//空或含换行，非法
        try {
            InternetAddress address = new InternetAddress(value, true); address.validate();//用JavaMail严格校验
            return address.getAddress().equals(value) && value.contains("@");//校验通过且带@才算合法
        } catch (Exception ignored) { return false; }
    }

    /** 校验SMTP配置是否齐全：host/port/账号/授权码/发件人都在，且配了SSL或STARTTLS之一。 */
    public boolean configured() {
        var c = properties.getSend().getPackingMail();//取SMTP配置
        return c != null && c.getHost() != null && !c.getHost().isBlank() && c.getPort() != null
                && c.getPort() > 0 && c.getPort() <= 65535 && validAddress(c.getUsername())
                && validAddress(c.getFrom()) && c.getPassword() != null && !c.getPassword().isBlank()
                && (Boolean.TRUE.equals(c.getSsl()) || Boolean.TRUE.equals(c.getStarttls()));//各项齐全且配了加密方式
    }

    /** 创建邮件发送器（拆出来便于测试时替换）。 */
    protected JavaMailSenderImpl createSender() { return new JavaMailSenderImpl(); }

    /** 主题只接受本类生成的格式（原主题或带“第N次提醒”后缀），防止别的通知串进来。 */
    private static boolean validSubject(String value) {
        return value != null && value.startsWith(SUBJECT);
    }

    /**
     * 主题按重发次数加后缀；正文仍然只有一个链接，不掺任何提示文字。
     * 首次发送(attemptNo=1)返回原主题，与历史行为完全一致。
     */
    public static String subject(int attemptNo) {
        return attemptNo > 1 ? SUBJECT + "（第" + attemptNo + "次提醒）" : SUBJECT;
    }

    /** 发一封关联货件催办邮件：校验配置→连SMTP→发正文(单个链接)→返回发送结果。 */
    public SendOutcome send(String recipient, String subject, String link) {
        if (!configured() || !validAddress(recipient) || !validSubject(subject))
            return new SendOutcome(SendState.UNSENT, null, null, "SMTP_CONFIG", false, "MAIL_CONFIG_INVALID");//SMTP没配、收件邮箱无效、或主题不对，直接返回未发送
        Transport transport = null;//SMTP传输对象，用于最后关闭
        boolean sending = false;//是否已经进入"正在发送"阶段（决定了结果是UNKNOWN还是UNSENT）
        try {
            var c = properties.getSend().getPackingMail();//取SMTP配置
            JavaMailSenderImpl sender = createSender();//创建邮件发送器
            sender.setHost(c.getHost()); sender.setPort(c.getPort());//设置服务器地址和端口
            Properties options = sender.getJavaMailProperties();//邮件属性
            options.setProperty("mail.smtp.auth", "true");//需要认证
            options.setProperty("mail.smtp.ssl.enable", Boolean.toString(Boolean.TRUE.equals(c.getSsl())));//按配置开SSL
            options.setProperty("mail.smtp.starttls.enable", Boolean.toString(Boolean.TRUE.equals(c.getStarttls())));//按配置开STARTTLS
            options.setProperty("mail.smtp.starttls.required", Boolean.toString(Boolean.TRUE.equals(c.getStarttls())));//STARTTLS强制
            options.setProperty("mail.smtp.ssl.checkserveridentity", "true");//校验服务器身份
            options.setProperty("mail.smtp.connectiontimeout", "10000");//连接超时10秒
            options.setProperty("mail.smtp.timeout", "15000");//读超时15秒
            options.setProperty("mail.smtp.writetimeout", "15000");//写超时15秒
            MimeMessage mail = sender.createMimeMessage();//创建邮件对象
            mail.setFrom(new InternetAddress(c.getFrom()));//发件人
            mail.setRecipient(Message.RecipientType.TO, new InternetAddress(recipient));//收件人
            mail.setSubject(subject, StandardCharsets.UTF_8.name());//主题（UTF-8编码）
            setBody(mail, link);//正文（单个链接）
            mail.setSentDate(new Date()); mail.saveChanges();//发送时间 + 保存改动
            transport = sender.getSession().getTransport("smtp");//拿SMTP传输通道
            transport.connect(c.getHost(), c.getPort(), c.getUsername(), c.getPassword());//用账号密码连SMTP服务器
            sending = true;//已经连接成功，进入发送阶段
            transport.sendMessage(mail, mail.getAllRecipients());//真正发送
            return new SendOutcome(SendState.SUCCESS, null, null, "SMTP_ACCEPTED", false, "SMTP_ACCEPTED");//发送成功
        } catch (AuthenticationFailedException exception) {
            return new SendOutcome(SendState.UNSENT, null, null, "SMTP_AUTH", false, "SMTP_AUTH_FAILED");//认证失败，明确没发出去
        } catch (Exception exception) {
            return new SendOutcome(sending ? SendState.UNKNOWN : SendState.UNSENT, null, null,
                    sending ? "SMTP_SEND" : "SMTP_CONNECT", !sending, exception.getClass().getSimpleName());//已经连上才报UNKNOWN，还没连上报UNSENT
        } finally {
            if (transport != null) try { transport.close(); } catch (Exception ignored) { }//最后关闭传输通道
        }
    }

    /**
     * 正文只放一个可点击的链接，不加其他文字。
     * 地址必须带 http/https 才转成超链接，避免把异常配置渲染成可点的伪协议。
     */
    private static void setBody(MimeMessage mail, String link) throws MessagingException {
        String url = link == null ? "" : link.trim();//链接去首尾空格
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            mail.setText(url, StandardCharsets.UTF_8.name());//不是http/https，就当纯文本写（不渲染成超链接）
            return;
        }
        String escaped = escapeHtml(url);//把链接里的特殊字符转义，防止HTML注入
        mail.setContent("<a href=\"" + escaped
                + "\" style=\"color:#1d4ed8;text-decoration:underline\">" + escaped + "</a>",
                "text/html;charset=UTF-8");//渲染成可点击的蓝色下划线超链接
    }

    /** 把字符串里的HTML特殊字符转义，防止拼进HTML时被当成标签解析。 */
    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
