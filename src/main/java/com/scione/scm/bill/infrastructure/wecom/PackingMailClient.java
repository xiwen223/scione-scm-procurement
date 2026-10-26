package com.scione.scm.bill.infrastructure.wecom;

import com.scione.scm.bill.config.SopReminderProperties;
import com.scione.scm.bill.infrastructure.wecom.WeComWebhookClient.SendOutcome;
import com.scione.scm.bill.infrastructure.wecom.WeComWebhookClient.SendState;
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
    public static final String SUBJECT = "关联货件催办";
    private final SopReminderProperties properties;

    public static boolean validAddress(String value) {
        if (value == null || value.isBlank() || value.contains("\r") || value.contains("\n")) return false;
        try {
            InternetAddress address = new InternetAddress(value, true); address.validate();
            return address.getAddress().equals(value) && value.contains("@");
        } catch (Exception ignored) { return false; }
    }

    /** 主题只接受本类生成的格式（原主题或带“第N次提醒”后缀），防止别的通知串进来。 */
    private static boolean validSubject(String value) {
        return value != null && value.startsWith(SUBJECT);
    }

    public boolean configured() {
        var c = properties.getSend().getPackingMail();
        return c != null && c.getHost() != null && !c.getHost().isBlank() && c.getPort() != null
                && c.getPort() > 0 && c.getPort() <= 65535 && validAddress(c.getUsername())
                && validAddress(c.getFrom()) && c.getPassword() != null && !c.getPassword().isBlank()
                && (Boolean.TRUE.equals(c.getSsl()) || Boolean.TRUE.equals(c.getStarttls()));
    }

    protected JavaMailSenderImpl createSender() { return new JavaMailSenderImpl(); }

    /**
     * 主题按重发次数加后缀；正文仍然只有一个链接，不掺任何提示文字。
     * 首次发送(attemptNo=1)返回原主题，与历史行为完全一致。
     */
    public static String subject(int attemptNo) {
        return attemptNo > 1 ? SUBJECT + "（第" + attemptNo + "次提醒）" : SUBJECT;
    }

    public SendOutcome send(String recipient, String subject, String link) {
        if (!configured() || !validAddress(recipient) || !validSubject(subject))
            return new SendOutcome(SendState.UNSENT, null, null, "SMTP_CONFIG", false, "MAIL_CONFIG_INVALID");
        Transport transport = null;
        boolean sending = false;
        try {
            var c = properties.getSend().getPackingMail();
            JavaMailSenderImpl sender = createSender();
            sender.setHost(c.getHost()); sender.setPort(c.getPort());
            Properties options = sender.getJavaMailProperties();
            options.setProperty("mail.smtp.auth", "true");
            options.setProperty("mail.smtp.ssl.enable", Boolean.toString(Boolean.TRUE.equals(c.getSsl())));
            options.setProperty("mail.smtp.starttls.enable", Boolean.toString(Boolean.TRUE.equals(c.getStarttls())));
            options.setProperty("mail.smtp.starttls.required", Boolean.toString(Boolean.TRUE.equals(c.getStarttls())));
            options.setProperty("mail.smtp.ssl.checkserveridentity", "true");
            options.setProperty("mail.smtp.connectiontimeout", "10000");
            options.setProperty("mail.smtp.timeout", "15000");
            options.setProperty("mail.smtp.writetimeout", "15000");
            MimeMessage mail = sender.createMimeMessage();
            mail.setFrom(new InternetAddress(c.getFrom()));
            mail.setRecipient(Message.RecipientType.TO, new InternetAddress(recipient));
            mail.setSubject(subject, StandardCharsets.UTF_8.name());
            setBody(mail, link);
            mail.setSentDate(new Date()); mail.saveChanges();
            transport = sender.getSession().getTransport("smtp");
            transport.connect(c.getHost(), c.getPort(), c.getUsername(), c.getPassword());
            sending = true;
            transport.sendMessage(mail, mail.getAllRecipients());
            return new SendOutcome(SendState.SUCCESS, null, null, "SMTP_ACCEPTED", false, "SMTP_ACCEPTED");
        } catch (AuthenticationFailedException exception) {
            return new SendOutcome(SendState.UNSENT, null, null, "SMTP_AUTH", false, "SMTP_AUTH_FAILED");
        } catch (Exception exception) {
            return new SendOutcome(sending ? SendState.UNKNOWN : SendState.UNSENT, null, null,
                    sending ? "SMTP_SEND" : "SMTP_CONNECT", !sending, exception.getClass().getSimpleName());
        } finally {
            if (transport != null) try { transport.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * 正文只放一个可点击的链接，不加其他文字。
     * 地址必须带 http/https 才转成超链接，避免把异常配置渲染成可点的伪协议。
     */
    private static void setBody(MimeMessage mail, String link) throws MessagingException {
        String url = link == null ? "" : link.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            mail.setText(url, StandardCharsets.UTF_8.name());
            return;
        }
        String escaped = escapeHtml(url);
        mail.setContent("<a href=\"" + escaped
                + "\" style=\"color:#1d4ed8;text-decoration:underline\">" + escaped + "</a>",
                "text/html;charset=UTF-8");
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
