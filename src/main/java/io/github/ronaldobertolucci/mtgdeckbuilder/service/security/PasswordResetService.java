package io.github.ronaldobertolucci.mtgdeckbuilder.service.security;

import io.github.ronaldobertolucci.mtgdeckbuilder.exception.InvalidTokenException;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.TokenExpiredException;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.security.PasswordResetToken;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.user.User;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.PasswordResetTokenRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.UserRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.email.EmailService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final Logger logger = LoggerFactory.getLogger(PasswordResetService.class);
    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    @Value("${app.frontend.url}")
    private String frontendUrl;
    @Value("${app.name}")
    private String appName;
    @Value("${password.reset.token.expiry.hours:24}")
    private Integer tokenExpiryHours;

    @Transactional
    public void createPasswordResetToken(String email) {
        // Busca usuário por email (não username)
        Optional<User> userOptional = userRepository.findByEmailWithRoles(email);

        if (userOptional.isEmpty()) {
            // Por segurança, não revelar que o email não existe
            logger.info("Password reset requested for non-existent email: {}", email);
            return;
        }

        User user = userOptional.get();
        if (!user.isEnabled()) {
            logger.info("Password reset requested for disabled user: {}", email);
            return;
        }

        // Remove tokens anteriores do usuário
        tokenRepository.deleteByUserId(user.getId());

        // Gera novo token
        String token = UUID.randomUUID().toString();

        PasswordResetToken resetToken = new PasswordResetToken(token, user, tokenExpiryHours);
        tokenRepository.save(resetToken);

        // Envia email
        sendResetEmail(user.getEmail(), token);

        logger.info("Password reset token created for user: {}", user.getEmail());
    }

    public void validatePasswordResetToken(String token) {
        PasswordResetToken resetToken = tokenRepository.findByToken(token)
                .orElseThrow(() -> new InvalidTokenException("Invalid token"));

        if (!resetToken.isValid()) {
            throw new TokenExpiredException("Token expired or already used");
        }
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        PasswordResetToken resetToken = tokenRepository.findByToken(token)
                .orElseThrow(() -> new InvalidTokenException("Invalid token"));

        if (!resetToken.isValid()) {
            throw new TokenExpiredException("Token expired or already used");
        }

        // Atualiza senha
        User user = resetToken.getUser();
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // Marca token como usado
        resetToken.setUsed(true);
        tokenRepository.save(resetToken);

        logger.info("Password reset successfully for user: {}", user.getEmail());
    }

    private void sendResetEmail(String email, String token) {
        String resetUrl = frontendUrl + "/reset-password?token=" + token;
        String htmlContent = buildEmailTemplate(resetUrl);

        try {
            emailService.sendHtmlEmail(
                    email,
                    appName + " - Redefinição de senha",
                    htmlContent
            );
        } catch (Exception e) {
            logger.error("Failed to send password reset email to: {}", email, e);
            throw new RuntimeException("Failed to send reset email");
        }
    }

    private String buildEmailTemplate(String resetUrl) {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset='UTF-8'>
                    <meta name='viewport' content='width=device-width, initial-scale=1.0'>
                </head>
                <body style='font-family: Arial, sans-serif; background-color: #f4f4f4; padding: 20px; margin: 0;'>
                    <div style='max-width: 600px; margin: 0 auto; background-color: white; padding: 30px; border-radius: 10px; box-shadow: 0 2px 4px rgba(0,0,0,0.1);'>
                        <h2 style='color: #333; margin-bottom: 20px; margin-top: 0;'>Redefinição de Senha</h2>
                        <p style='color: #555; line-height: 1.6;'>Olá,</p>
                        <p style='color: #555; line-height: 1.6;'>Você solicitou a redefinição de senha para %s.</p>
                        <p style='color: #555; line-height: 1.6;'>Clique no botão abaixo para criar uma nova senha:</p>
                        <div style='text-align: center; margin: 30px 0;'>
                            <a href='%s'
                               style='display: inline-block; padding: 14px 32px; background-color: #007bff;
                                      color: white; text-decoration: none; border-radius: 5px; font-weight: bold;
                                      font-size: 16px;'>Redefinir Senha</a>
                        </div>
                        <p style='color: #666; font-size: 14px; line-height: 1.6;'>Ou copie e cole este link no seu navegador:</p>
                        <p style='color: #007bff; word-break: break-all; font-size: 12px; background-color: #f8f9fa;
                                  padding: 10px; border-radius: 4px;'>%s</p>
                        <hr style='border: none; border-top: 1px solid #eee; margin: 30px 0;'>
                        <p style='color: #999; font-size: 12px; line-height: 1.6;'>
                            <strong>Importante:</strong> Este link expirará em %d horas.
                        </p>
                        <p style='color: #999; font-size: 12px; line-height: 1.6;'>
                            Se você não solicitou a redefinição de senha, ignore este email.
                            Sua senha permanecerá inalterada.
                        </p>
                        <p style='color: #999; font-size: 12px; margin-top: 30px; line-height: 1.6;'>
                            Atenciosamente,<br>
                            <strong>Equipe %s</strong>
                        </p>
                    </div>
                </body>
                </html>
                """.formatted(appName, resetUrl, resetUrl, tokenExpiryHours, appName);
    }

    @Scheduled(cron = "${password.reset.cleanup.cron:0 0 2 * * ?}")
    @Transactional
    public void purgeExpiredTokens() {
        logger.info("Starting cleanup of expired password reset tokens");
        tokenRepository.deleteExpiredTokens(LocalDateTime.now());
        logger.info("Expired tokens cleanup completed");
    }
}