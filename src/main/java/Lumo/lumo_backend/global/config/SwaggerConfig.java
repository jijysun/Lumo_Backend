package Lumo.lumo_backend.global.config;

// Swagger(springdoc) 제거로 전체 주석 처리 — 20260813
// 되살릴 때는 build.gradle 의 springdoc / swagger-annotations 의존성도 함께 복구할 것.

// import io.swagger.v3.oas.models.Components;
// import io.swagger.v3.oas.models.OpenAPI;
// import io.swagger.v3.oas.models.info.Info;
// import io.swagger.v3.oas.models.security.SecurityRequirement;
// import io.swagger.v3.oas.models.security.SecurityScheme;
// import io.swagger.v3.oas.models.servers.Server;
// import org.springframework.context.annotation.Bean;
// import org.springframework.context.annotation.Configuration;

// @Configuration
// public class SwaggerConfig {
//
//     @Bean
//     public OpenAPI swagger() {
//         Info info = new Info().title("LUMO API Document").description("LUMO의 API 문서입니다.").version("0.0.1");
//
//         // JWT 토큰 헤더 방식
//         String securityScheme = "JWT TOKEN";
//         SecurityRequirement securityRequirement = new SecurityRequirement().addList(securityScheme);
//
//         Components components = new Components()
//                 .addSecuritySchemes(securityScheme, new SecurityScheme()
//                         .name(securityScheme)
//                         .type(SecurityScheme.Type.HTTP)
//                         .scheme("Bearer")
//                         .bearerFormat("JWT"));
//
//         return new OpenAPI()
//                 .info(info)
//                 .addServersItem(new Server().url("/"))
//                 .addSecurityItem(securityRequirement)
//                 .components(components);
//     }
// }
