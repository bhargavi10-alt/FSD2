
package com.examtrack.util;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Properties;

public class EmailUtil {

    public static void sendVerificationCode(
            String recipientEmail,
            String studentName,
            String otp) throws MessagingException {

        String senderEmail =
                System.getenv("EXAMTRACK_MAIL_USERNAME");

        String appPassword =
                System.getenv("EXAMTRACK_MAIL_APP_PASSWORD");

        if (senderEmail == null || senderEmail.isBlank()
                || appPassword == null || appPassword.isBlank()) {
            throw new MessagingException(
                    "Gmail environment variables are not configured.");
        }

        Properties properties = new Properties();
        properties.put("mail.smtp.host", "smtp.gmail.com");
        properties.put("mail.smtp.port", "587");
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.starttls.enable", "true");
        properties.put("mail.smtp.connectiontimeout", "10000");
        properties.put("mail.smtp.timeout", "10000");
        properties.put("mail.smtp.writetimeout", "10000");

        Session mailSession = Session.getInstance(
                properties,
                new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(
                                senderEmail, appPassword);
                    }
                });

        Message message = new MimeMessage(mailSession);
        message.setFrom(new InternetAddress(senderEmail));
        message.setRecipients(
                Message.RecipientType.TO,
                InternetAddress.parse(recipientEmail, true));

        message.setSubject("ExamTrack - Email Verification Code");

        String emailBody =
                "Hello " + studentName + ",\n\n"
                + "Your ExamTrack verification code is: " + otp + "\n\n"
                + "This code expires in 5 minutes.\n"
                + "Do not share this code with anyone.\n\n"
                + "If you did not request this code, ignore this email.\n\n"
                + "Regards,\nExamTrack Team";

        message.setText(emailBody);
        message.setHeader(
                "Content-Type", "text/plain; charset=UTF-8");

        Transport.send(message);
    }
}
