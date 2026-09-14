package io.github.ronaldobertolucci.mtgdeckbuilder.service.email;

import io.github.ronaldobertolucci.mtgdeckbuilder.model.security.EmailVerificationToken;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.user.User;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.EmailVerificationTokenRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private static final Logger logger = LoggerFactory.getLogger(EmailVerificationService.class);
    private final EmailVerificationTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;

    @Value("${app.email.verification.expiry.hours}")
    private int expiryHours;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    @Transactional
    public void sendVerificationEmail(User user) {
        EmailVerificationToken token = EmailVerificationToken.builder()
                .token(UUID.randomUUID().toString())
                .user(user)
                .expiryDate(LocalDateTime.now().plusHours(expiryHours))
                .build();

        tokenRepository.save(token);

        String link = frontendUrl + "/verify-email?token=" + token.getToken();
        String html = buildEmailHtml(user.getFirstName(), link);

        emailService.sendHtmlEmail(user.getEmail(), "Verifique seu email", html);
    }

    @Transactional
    public void verifyEmail(String rawToken) {
        EmailVerificationToken token = tokenRepository.findByToken(rawToken)
                .orElseThrow(() -> new IllegalArgumentException("Invalid verification token"));

        if (token.isUsed()) {
            throw new IllegalStateException("Token has already been used");
        }

        if (token.isExpired()) {
            throw new IllegalStateException("Token has expired");
        }

        token.setUsed(true);

        User user = token.getUser();
        user.setEnabled(true);
    }

    @Transactional
    public void resendVerificationEmail(String email) {
        Optional<User> userOptional = userRepository.findByEmailWithRoles(email);

        if (userOptional.isEmpty()) {
            // Por segurança, não revelar que o email não existe
            logger.info("Password reset requested for non-existent email: {}", email);
            return;
        }

        User user = userOptional.get();

        if (user.isEnabled()) {
            throw new IllegalStateException("Account is already verified");
        }

        sendVerificationEmail(user);
    }

    private String buildEmailHtml(String firstName, String verificationLink) {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset='UTF-8'>
                <meta name='viewport' content='width=device-width, initial-scale=1.0'>
            </head>
            <body style='font-family: Arial, sans-serif; background-color: #f4f4f4; padding: 20px; margin: 0;'>
                <div style='max-width: 600px; margin: 0 auto; background-color: white; padding: 30px; border-radius: 10px; box-shadow: 0 2px 4px rgba(0,0,0,0.1);'>
                    <h2 style='color: #333; margin-bottom: 20px; margin-top: 0;'>Verificação de Email</h2>
                    <p style='color: #555; line-height: 1.6;'>Olá, %s!</p>
                    <p style='color: #555; line-height: 1.6;'>Sua conta foi criada com sucesso. Para ativá-la, confirme seu endereço de email clicando no botão abaixo:</p>
                    <div style='text-align: center; margin: 30px 0;'>
                        <a href='%s'
                           style='display: inline-block; padding: 14px 32px; background-color: #007bff;
                                  color: white; text-decoration: none; border-radius: 5px; font-weight: bold;
                                  font-size: 16px;'>Verificar Email</a>
                    </div>
                    <p style='color: #666; font-size: 14px; line-height: 1.6;'>Ou copie e cole este link no seu navegador:</p>
                    <p style='color: #007bff; word-break: break-all; font-size: 12px; background-color: #f8f9fa;
                              padding: 10px; border-radius: 4px;'>%s</p>
                    <hr style='border: none; border-top: 1px solid #eee; margin: 30px 0;'>
                    <p style='color: #999; font-size: 12px; line-height: 1.6;'>
                        <strong>Importante:</strong> Este link expirará em %d horas.
                    </p>
                    <p style='color: #999; font-size: 12px; line-height: 1.6;'>
                        Se você não criou uma conta, ignore este email.
                        Nenhuma alteração será feita.
                    </p>
                    <p style='color: #999; font-size: 12px; margin-top: 30px; line-height: 1.6;'>
                        Atenciosamente,<br>
                        <strong>Equipe %s</strong>
                    </p>
                </div>
            </body>
            </html>
            """.formatted(firstName, verificationLink, verificationLink, expiryHours, "MTG Deck Builder");
    }
}