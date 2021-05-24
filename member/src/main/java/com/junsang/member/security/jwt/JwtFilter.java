package com.junsang.member.security.jwt;
import com.junsang.member.security.jwt.exception.ErrorCode;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CachePut;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 요청 흐름
 * : Request  ===>  Filter  ===>  DispatcherServlet  ===>  Interceptior  ===>  Controller
 *
 * 이 클래스에서는 OncePerRequestFilter 사용
 * : 필터가 재실행되는 경우를 방지한다.
 * : 요청당 필터는 정확히 1번만 실행 된다.
 *
 * 문제
 * : 처음에 필터가 2번 실행 되었다. 왜?
 * : 스프링 부트를 사용하다 보면 가장 처음으로 만나는 “@ComponentScan"와 “@Component"에 있다. “@SpringBootApplication"는 여러 어노테이션의 묶음이고 그 안에는 “@ComponentScan"가 있어서 빈들을 자동으로 등록해주는 역할을 하게 되는데 필터에 “@Component"가 설정되어 있어 자동으로 등록이 되었고, 두번째 방법인 “@WebFilter + @ServletComponentScan” 조합으로 한번 더 등록되어버린 것이다. 즉, 동일한 필터가 두번 등록된 상황.
 *   “/test” 에서 한번 로깅된건 “@Component” 에 의해 등록된 필터로 인해 urlPattern 이 적용되지 않았으니 한번 로깅이 되고, urlPattern 이 적용된 필터에서는 urlPattern에 맞지 않으니 로깅이 안되는건 당연. 그 다음 “/filtered/test” 은 “@Component” 에 의해 등록된 필터로 한번 로깅, 그다음 “@WebFilter"로 등록된 필터에서 urlPattern에 맞는 url 이다보니 로깅이 되서 총 두번 로깅이 되게 된다.
 *   즉, 모든 url에 필터를 적용 할 것이라면 “@ComponentScan + @Component” 조합으로 해도 될 것 같고, 명시적으로 특정 urlPattern 에만 필터를 적용한다거나 필터의 다양한 설정 (우선순위, 필터이름 등) 을 하게 되는 경우엔 위에서 알려준 “FilterRegistrationBean” 이나 “@WebFilter + @ServletComponentScan"을 사용해서 상황에 맞도록 설정하는게 중요할 것 같다
 */
@Slf4j
public class JwtFilter extends OncePerRequestFilter {

    private String AUTHORITIES_ACCESS_TOKEN_HEADER = "AuthHeader";
    private String AUTHORITIES_REFRESH_TOKEN_HEADER = "refreshTokenHeader";
    private JwtProvider jwtProvider;


    @Autowired
    private JwtException jwtException;

    public JwtFilter(JwtProvider jwtProvider) {
        this.jwtProvider = jwtProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        HttpServletRequest httpServletRequest = (HttpServletRequest) request;

        // 현재 URI
        String requestURI = httpServletRequest.getRequestURI();

        // 헤더에서 토큰 추출
        String accessToken = resolveAccessToken(httpServletRequest);
        String refreshToken = resolveRefreshToken(httpServletRequest);

        // 토큰 재발급 여부
        boolean isReissued = false;


        /**
         *
         * Access Token & Refresh Token 유효성 검사
         *
         * **/

        // Access Token 존재하는 경우
        if (StringUtils.hasText(accessToken) ) {
            log.info("===== [JS Log] 사용자 '{}' 님의 Access Token 이 발견되었습니다 : '{}' ", request.getAttribute("email"), accessToken);

            // Access Token 유효성 검증
            String exceptionNm = jwtProvider.validateToken(accessToken);
            if ("".equals(exceptionNm)) {
                log.info("===== [JS Log] 사용자 '{}' 님이 토큰 유효성 검증을 통과하였습니다.", request.getAttribute("email"));

                // Access Token Payload 검증


                // 실제로 Access Token 이 만료되지는 않았지만, 만료 시간에 시간이 가까울 경우 재발급 (이거 없어도 될 것 같은데?)
                if (jwtProvider.whetherTheTokenIsReissued(accessToken)) {
                    log.info("===== [JS Log] 사용자 '{}' 님의 Access Token 은 재발급 대상 입니다.", request.getAttribute("email"));
                    isReissued = true;  // 재발급 요청
                } else {

                    // 토큰에서 유저 정보를 가져와 Auth 객체를 만듬
                    Authentication auth2 = jwtProvider.getAuthentication(accessToken);

                    // 요청으로 들어온 토큰 그대로 담아 반환
                    request.setAttribute("AccessTokenData", accessToken);
                    request.setAttribute("RefreshTokenData", refreshToken);

                    // 시큐리티 컨텍스트에 Auth 객체 저장
                    SecurityContextHolder.getContext().setAuthentication(auth2);
                    log.info("===== [JS Log] Security Context 에 '{} 인증 정보를 저장했습니다. URI: {} '", auth2.getName(), requestURI);
                }
            }

            // Access Token 만료됨
            else if("EXPIRED_TOKEN".equals(exceptionNm)) {
                log.info("===== [JS Log] 사용자 '{}' 님의 Access Token 은 만료된 토큰 입니다.", request.getAttribute("email"));

                // Refresh Token 이 존재하는지 확인
                if (!StringUtils.hasText(refreshToken) ) {
                    log.info("===== [JS Log] 사용자 '{}' 님의 Refresh Token 을 찾았습니다.", request.getAttribute("email"));
                    isReissued = true;
                }
                // ?
            }

            // Access Token 유효성 검증 실패
            else {
                log.info("===== [JS Log] 사용자 '{}' 님이 토큰 유효성 검증에 실패하였습니다.", request.getAttribute("email"));
                request.setAttribute("exception", exceptionNm);
            }
        }

        // Access Token [N]  ||  Refresh Token [Y]
        else if (StringUtils.hasText(refreshToken) ) {
            log.info("===== [JS Log] 사용자 '{}' 님의 Access Token 은 발견하지 못하였으며, Refresh Token 은 발견되었습니다. : '{}' ", request.getAttribute("email"), refreshToken);

            // Refresh Token 유효성 검사
            String exceptionNm = jwtProvider.validateToken(refreshToken);
            if ("".equals(exceptionNm)) {
                log.info("===== [JS Log] 사용자 '{}' 님이 토큰 유효성 검증을 통과하였습니다.", request.getAttribute("email"));

                // Refresh Token Payload 검증


                // Refresh Token 을 이용해 Access Token 구하기
                accessToken = "1";

                // 토큰 담아서 넘겨주기
                request.setAttribute("AccessTokenData", accessToken);
                request.setAttribute("RefreshTokenData", refreshToken);

                // 토큰에서 유저 정보를 가져와 Auth 객체를 만듬
                Authentication auth2 = jwtProvider.getAuthentication(accessToken);

                // 시큐리티 컨텍스트에 Auth 객체 저장
                SecurityContextHolder.getContext().setAuthentication(auth2);
                log.debug("Security Context 에 '{} 인증 정보를 저장했습니다. URI: {} '", auth2.getName(), requestURI);
            }

            // Refresh Token 유효성 검증 실패
            else {
                log.info("===== [JS Log] 사용자 '{}' 님이 토큰 유효성 검증에 실패하였습니다.", request.getAttribute("email"));
                request.setAttribute("exception", ErrorCode.INVALID_TOKEN.getCode());
            }
        }

        // Access Token [N]  ||  Refresh Token [N]
        else {
            log.info("===== [JS Log] 사용자 '{}' 님의 Access Token 과 Refresh Token 둘 다 존재하지 않습니다. : '{}' ", request.getAttribute("email"), refreshToken);
            log.info("===== [JS Log] 사용자 '{}' 님, 신규 토큰 생성 요청 입니다.", request.getAttribute("email"));

            isReissued = true;
        }

        // 토큰 발급을 위한 Controller 이동
        if (isReissued) {
            log.info("===== [JS Log] 사용자 '{}' 님, 신규 토큰 및 재발급 요청 입니다.", request.getAttribute("email"));
            request.getRequestDispatcher("/api/jwtLogin").forward(request, response);
        } else {
            log.info("===== [JS Log] 사용자 '{}' 님, 인증 완료 입니다.", request.getAttribute("email"));
            filterChain.doFilter(request, response);
        }
    }


    /**
     * 헤더에서 토큰 정보를 추출하여 가공 후 반환
     */
    public String resolveAccessToken(HttpServletRequest request) {
        // 헤더에서 토큰 정보를 추출
        String bearerToken = request.getHeader(AUTHORITIES_ACCESS_TOKEN_HEADER);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer "))
            return bearerToken.substring(7);

        return null;
    }

    public String resolveRefreshToken(HttpServletRequest request) {
        // 헤더에서 토큰 정보를 추출
        String bearerToken = request.getHeader(AUTHORITIES_REFRESH_TOKEN_HEADER);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer "))
            return bearerToken.substring(7);

        return null;
    }
}