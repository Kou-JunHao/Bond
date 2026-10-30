package com.bond.transfer.contract;

import com.bond.common.dto.Result;
import com.bond.common.security.PublicEndpoints;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.multipart.MultipartFile;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 构建期 OpenAPI 生成器（task-121）。
 *
 * <p>为什么不是 springdoc？</p>
 * <ul>
 *   <li>springdoc 的 {@code api-docs} 是<b>运行时</b>端点，本单要求线上不暴露；</li>
 *   <li>{@code springdoc-openapi-maven-plugin} 需要先启动应用（本仓库启动依赖 PG/RustFS），
 *       本地与 CI 都不可用。</li>
 * </ul>
 * <p>因此这里用「测试期反射扫描 Controller + DTO」生成静态 {@code docs/openapi.json}：
 * 构建（{@code mvn test}）即产出，运行时零暴露面。生成范围：bond-transfer 的
 * {@code com.bond.transfer.controller} + bond-auth 的 {@code com.bond.auth.controller}
 * （后者从同级模块 target/classes 读取，完整 reactor 构建时可用）。</p>
 */
public final class OpenApiGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private OpenApiGenerator() {
    }

    /** 生成并返回 OpenAPI 文档对象。 */
    public static Map<String, Object> generate() throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("openapi", "3.0.3");
        root.put("info", Map.of(
                "title", "Bond API",
                "version", "v1",
                "description", "构建期反射生成的静态契约（task-121）。运行时无 /v3/api-docs 端点。"));
        root.put("servers", List.of(Map.of("url", "https://{host}",
                "variables", Map.of("host", Map.of("default", "api.example.com")))));

        Map<String, Object> paths = new TreeMap<>();
        Map<String, Object> schemas = new TreeMap<>();
        int operations = 0;

        for (Class<?> controller : findControllers()) {
            String[] basePaths = mappingPaths(controller);
            for (Method method : controller.getDeclaredMethods()) {
                MappingRef mapping = mappingOf(method);
                if (mapping == null) {
                    continue;
                }
                for (String base : basePaths) {
                    for (String sub : mapping.paths()) {
                        String path = joinPath(base, sub);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> pathItem = (Map<String, Object>) paths
                                .computeIfAbsent(path, key -> new TreeMap<>());
                        for (String httpMethod : mapping.methods()) {
                            pathItem.put(httpMethod.toLowerCase(Locale.ROOT),
                                    operation(controller, method, httpMethod, path, schemas));
                            operations++;
                        }
                    }
                }
            }
        }

        root.put("paths", paths);
        root.put("components", Map.of(
                "securitySchemes", Map.of(
                        "bearerAuth", Map.of("type", "http", "scheme", "bearer", "bearerFormat", "JWT"),
                        "basicAuth", Map.of("type", "http", "scheme", "basic")),
                "schemas", schemas));

        return Map.of(
                "document", root,
                "operationCount", operations,
                "pathCount", paths.size(),
                "schemaCount", schemas.size());
    }

    /** 生成到 {@code BondServer/docs/openapi.json}（相对当前模块目录）。 */
    public static Path writeToDocs() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> document = (Map<String, Object>) generate().get("document");
        Path docs = Paths.get("..", "docs");
        Files.createDirectories(docs);
        Path target = docs.resolve("openapi.json");
        Files.writeString(target, MAPPER.writeValueAsString(document) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        return target.toAbsolutePath().normalize();
    }

    // ── controller 发现 ──────────────────────────────────────────────────

    private static List<Class<?>> findControllers() throws Exception {
        Set<Class<?>> controllers = new LinkedHashSet<>();
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        for (var definition : scanner.findCandidateComponents("com.bond.transfer")) {
            controllers.add(Class.forName(definition.getBeanClassName()));
        }
        Path authClasses = Paths.get("..", "bond-auth", "target", "classes").toAbsolutePath().normalize();
        if (Files.isDirectory(authClasses)) {
            URLClassLoader loader = new URLClassLoader(
                    new URL[]{authClasses.toUri().toURL()}, OpenApiGenerator.class.getClassLoader());
            Path packageDir = authClasses.resolve("com/bond/auth/controller");
            if (Files.isDirectory(packageDir)) {
                try (Stream<Path> files = Files.list(packageDir)) {
                    for (Path file : files.toList()) {
                        String name = file.getFileName().toString();
                        if (name.endsWith("Controller.class")) {
                            String className = "com.bond.auth.controller."
                                    + name.substring(0, name.length() - ".class".length());
                            controllers.add(Class.forName(className, false, loader));
                        }
                    }
                }
            }
        }
        return new ArrayList<>(controllers);
    }

    // ── 映射注解解析 ────────────────────────────────────────────────────

    private record MappingRef(List<String> paths, List<String> methods) {
    }

    private static String[] mappingPaths(Class<?> controller) {
        RequestMapping mapping = controller.getAnnotation(RequestMapping.class);
        if (mapping == null || mapping.value().length == 0) {
            return new String[]{""};
        }
        return mapping.value();
    }

    private static MappingRef mappingOf(Method method) throws Exception {
        Annotation mapping = firstNonNull(
                method.getAnnotation(GetMapping.class), method.getAnnotation(PostMapping.class),
                method.getAnnotation(PutMapping.class), method.getAnnotation(DeleteMapping.class),
                method.getAnnotation(PatchMapping.class));
        if (mapping != null) {
            List<String> paths = Arrays.asList((String[]) annotationValue(mapping));
            String httpMethod = mapping instanceof GetMapping ? "GET"
                    : mapping instanceof PostMapping ? "POST"
                    : mapping instanceof PutMapping ? "PUT"
                    : mapping instanceof DeleteMapping ? "DELETE" : "PATCH";
            return new MappingRef(paths.isEmpty() ? List.of("") : paths, List.of(httpMethod));
        }
        RequestMapping rm = method.getAnnotation(RequestMapping.class);
        if (rm == null) {
            return null;
        }
        List<String> paths = List.of(rm.value().length == 0 ? new String[]{""} : rm.value());
        List<String> methods = new ArrayList<>();
        if (rm.method().length == 0) {
            methods.add("GET");
        } else {
            for (RequestMethod requestMethod : rm.method()) {
                methods.add(requestMethod.name());
            }
        }
        return new MappingRef(paths, methods);
    }

    private static Object annotationValue(Annotation annotation) throws Exception {
        return annotation.annotationType().getMethod("value").invoke(annotation);
    }

    private static Annotation firstNonNull(Annotation... annotations) {
        for (Annotation annotation : annotations) {
            if (annotation != null) {
                return annotation;
            }
        }
        return null;
    }

    // ── operation 生成 ───────────────────────────────────────────────────

    private static Map<String, Object> operation(Class<?> controller, Method method, String httpMethod,
                                                 String path, Map<String, Object> schemas) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("operationId", controller.getSimpleName() + "_" + method.getName());
        operation.put("tags", List.of(controller.getSimpleName().replace("Controller", "")));
        operation.put("summary", method.getName());

        List<Object> parameters = new ArrayList<>();
        Map<String, Object> requestBody = null;
        boolean multipart = false;
        for (Parameter parameter : method.getParameters()) {
            if (parameter.isAnnotationPresent(RequestBody.class)) {
                requestBody = jsonBody(schemaFor(parameter.getParameterizedType(), schemas));
                continue;
            }
            if (parameter.isAnnotationPresent(PathVariable.class)) {
                PathVariable annotation = parameter.getAnnotation(PathVariable.class);
                parameters.add(pathParameter(name(annotation.value(), parameter), false));
            } else if (parameter.isAnnotationPresent(RequestParam.class)) {
                RequestParam annotation = parameter.getAnnotation(RequestParam.class);
                String paramName = name(annotation.value(), parameter);
                if (MultipartFile.class.isAssignableFrom(parameter.getType())) {
                    multipart = true;
                    parameters.add(formParameter(paramName, true));
                } else {
                    parameters.add(queryParameter(paramName, annotation.required(),
                            annotation.defaultValue(), parameter.getType()));
                }
            } else if (parameter.isAnnotationPresent(RequestHeader.class)) {
                RequestHeader annotation = parameter.getAnnotation(RequestHeader.class);
                parameters.add(headerParameter(name(annotation.value(), parameter), annotation.required()));
            }
        }
        if (multipart) {
            requestBody = Map.of("content", Map.of("multipart/form-data",
                    Map.of("schema", Map.of("type", "object"))), "required", true);
        }
        if (!parameters.isEmpty()) {
            operation.put("parameters", parameters);
        }
        if (requestBody != null) {
            operation.put("requestBody", requestBody);
        }

        Object responseSchema = responseSchema(method, schemas);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("description", "统一响应 Result{code,message,data}");
        response.put("content", Map.of("application/json", Map.of("schema", responseSchema)));
        operation.put("responses", Map.of("200", response));
        operation.put("security", isPublic(httpMethod, path) ? List.of() : List.of(Map.of("bearerAuth", List.of())));
        if (httpMethod.equals("GET") && path.startsWith("/api/files") && path.endsWith("/download")) {
            operation.put("responses", Map.of(
                    "200", streamingResponse("application/octet-stream"),
                    "206", streamingResponse("application/octet-stream"),
                    "416", Map.of("description", "Range 不合法")));
        }
        return operation;
    }

    private static Map<String, Object> streamingResponse(String contentType) {
        return Map.of(
                "description", "文件流（支持 Range/206）",
                "content", Map.of(contentType, Map.of("schema", Map.of("type", "string", "format", "binary"))));
    }

    private static Map<String, Object> jsonBody(Object schema) {
        return Map.of("content", Map.of("application/json", Map.of("schema", schema)), "required", true);
    }

    private static Object responseSchema(Method method, Map<String, Object> schemas) {
        Type returnType = method.getGenericReturnType();
        if (returnType instanceof ParameterizedType parameterized
                && parameterized.getRawType() == Result.class) {
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("type", "object");
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("code", Map.of("type", "integer"));
            properties.put("message", Map.of("type", "string"));
            properties.put("data", schemaFor(parameterized.getActualTypeArguments()[0], schemas));
            wrapper.put("properties", properties);
            return wrapper;
        }
        return schemaFor(returnType, schemas);
    }

    // ── schema 生成 ─────────────────────────────────────────────────────

    private static Object schemaFor(Type type, Map<String, Object> schemas) {
        if (type instanceof ParameterizedType parameterized) {
            Type raw = parameterized.getRawType();
            if (raw == List.class || raw == Set.class || raw == java.util.Collection.class) {
                return Map.of("type", "array",
                        "items", schemaFor(parameterized.getActualTypeArguments()[0], schemas));
            }
            if (raw == Map.class) {
                return Map.of("type", "object",
                        "additionalProperties", schemaFor(parameterized.getActualTypeArguments()[1], schemas));
            }
            if (raw == org.springframework.http.ResponseEntity.class) {
                return schemaFor(parameterized.getActualTypeArguments()[0], schemas);
            }
            if (raw instanceof Class<?> clazz) {
                return refOrPrimitive(clazz, schemas);
            }
            return Map.of("type", "object");
        }
        if (type instanceof Class<?> clazz) {
            return refOrPrimitive(clazz, schemas);
        }
        return Map.of("type", "object");
    }

    private static Object refOrPrimitive(Class<?> clazz, Map<String, Object> schemas) {
        if (clazz == String.class) {
            return Map.of("type", "string");
        }
        if (clazz == Long.class || clazz == long.class) {
            return Map.of("type", "integer", "format", "int64");
        }
        if (clazz == Integer.class || clazz == int.class) {
            return Map.of("type", "integer", "format", "int32");
        }
        if (clazz == Boolean.class || clazz == boolean.class) {
            return Map.of("type", "boolean");
        }
        if (clazz == Double.class || clazz == double.class || clazz == Float.class || clazz == float.class) {
            return Map.of("type", "number");
        }
        if (clazz == byte[].class) {
            return Map.of("type", "string", "format", "binary");
        }
        if (clazz.isEnum()) {
            List<String> values = Arrays.stream(clazz.getEnumConstants())
                    .map(Object::toString).toList();
            return Map.of("type", "string", "enum", values);
        }
        if (clazz.getName().startsWith("java.time.")) {
            return Map.of("type", "string", "format", "date-time");
        }
        if (clazz.isArray()) {
            return Map.of("type", "array", "items", refOrPrimitive(clazz.getComponentType(), schemas));
        }
        if (clazz.getName().startsWith("java.") || clazz.isPrimitive()) {
            return Map.of("type", "object");
        }
        String schemaName = clazz.getSimpleName();
        if (!schemas.containsKey(schemaName)) {
            schemas.put(schemaName, Map.of()); // 占位，防循环
            schemas.put(schemaName, describe(clazz, schemas));
        }
        return Map.of("$ref", "#/components/schemas/" + schemaName);
    }

    private static Map<String, Object> describe(Class<?> clazz, Map<String, Object> schemas) {
        Map<String, Object> properties = new LinkedHashMap<>();
        if (clazz.isRecord()) {
            for (RecordComponent component : clazz.getRecordComponents()) {
                properties.put(jsonName(component.getAccessor()),
                        withSerializationOverride(schemaFor(component.getGenericType(), schemas),
                                component.getAccessor()));
            }
        } else {
            for (Field field : clazz.getDeclaredFields()) {
                if (field.isSynthetic() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                properties.put(jsonName(field),
                        withSerializationOverride(schemaFor(field.getGenericType(), schemas), field));
            }
        }
        return Map.of("type", "object", "properties", properties);
    }

    /** 字段若显式 @JsonSerialize(using=ToStringSerializer.class)（雪花 ID 字符串化），schema 标 string。 */
    private static Object withSerializationOverride(Object schema,
                                                    java.lang.reflect.AnnotatedElement element) {
        JsonSerialize jsonSerialize = element.getAnnotation(JsonSerialize.class);
        if (jsonSerialize != null && jsonSerialize.using() == ToStringSerializer.class) {
            return Map.of("type", "string", "format", "int64");
        }
        return schema;
    }

    private static String jsonName(java.lang.reflect.AnnotatedElement element) {
        JsonProperty jsonProperty = element.getAnnotation(JsonProperty.class);
        if (jsonProperty != null && !jsonProperty.value().isEmpty()) {
            return jsonProperty.value();
        }
        if (element instanceof Field field) {
            return field.getName();
        }
        if (element instanceof Method method) {
            return method.getName();
        }
        return "value";
    }

    // ── 参数/安全 ───────────────────────────────────────────────────────

    private static String name(String annotationValue, Parameter parameter) {
        return annotationValue != null && !annotationValue.isBlank() ? annotationValue : parameter.getName();
    }

    private static Map<String, Object> pathParameter(String name, boolean required) {
        return Map.of("name", name, "in", "path", "required", true,
                "schema", Map.of("type", "string"));
    }

    private static Map<String, Object> queryParameter(String name, boolean required,
                                                      String defaultValue, Class<?> type) {
        Map<String, Object> parameter = new LinkedHashMap<>();
        parameter.put("name", name);
        parameter.put("in", "query");
        parameter.put("required", required);
        parameter.put("schema", refOrPrimitive(type, new LinkedHashMap<>()));
        if (defaultValue != null && !ValueConstants.DEFAULT_NONE.equals(defaultValue)) {
            parameter.put("summary", "default=" + defaultValue);
        }
        return parameter;
    }

    private static Map<String, Object> headerParameter(String name, boolean required) {
        return Map.of("name", name, "in", "header", "required", required,
                "schema", Map.of("type", "string"));
    }

    private static Map<String, Object> formParameter(String name, boolean required) {
        return Map.of("name", name, "in", "query", "required", required,
                "schema", Map.of("type", "string", "format", "binary"));
    }

    private static boolean isPublic(String httpMethod, String path) {
        String numeric = path.replaceAll("\\{[^}]+}", "1");
        String token = path.replaceAll("\\{[^}]+}", "a".repeat(43));
        if (PublicEndpoints.isPublic(httpMethod, numeric) || PublicEndpoints.isPublic(httpMethod, token)) {
            return true;
        }
        // WebDAV 走 Basic 认证，不是 bearer
        return path.startsWith("/dav");
    }

    private static String joinPath(String base, String sub) {
        String prefix = base == null ? "" : base.trim();
        String suffix = sub == null ? "" : sub.trim();
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        if (suffix.isEmpty()) {
            return prefix;
        }
        if (!suffix.startsWith("/")) {
            suffix = "/" + suffix;
        }
        return (prefix.equals("/") ? "" : prefix) + suffix;
    }

    /** 测试入口：写文件并打印统计。 */
    public static void main(String[] args) throws Exception {
        Path target = writeToDocs();
        Map<String, Object> stats = generate();
        System.out.println("openapi.json written to " + target
                + " (paths=" + stats.get("pathCount")
                + ", operations=" + stats.get("operationCount")
                + ", schemas=" + stats.get("schemaCount") + ")");
    }
}
