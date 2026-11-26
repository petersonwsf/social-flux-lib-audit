package com.socialflux.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.ClassUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * IP e user agent da requisição HTTP em curso, para os eventos que não os informam. Atrás do gateway o IP real vem do
 * {@code X-Forwarded-For} (o serviço usa {@code forward-headers-strategy: framework}). Sem requisição (consumidor de
 * evento, job agendado) ou sem servlet no classpath, devolve {@code null}.
 */
final class RequestClient {

    /** Dados do cliente; vão só para o audit-service, nunca para o log. */
    record Info(String ip, String userAgent) {
    }

    private static final boolean SERVLET = ClassUtils.isPresent("jakarta.servlet.http.HttpServletRequest",
            RequestClient.class.getClassLoader());

    private RequestClient() {
    }

    static Info current() {
        return SERVLET ? ServletLookup.current() : null;
    }

    /** Isolada para que a classe só seja carregada quando o servlet existe. */
    private static final class ServletLookup {

        static Info current() {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if (attributes instanceof ServletRequestAttributes servlet) {
                HttpServletRequest request = servlet.getRequest();
                return new Info(request.getRemoteAddr(), request.getHeader("User-Agent"));
            }
            return null;
        }
    }
}
