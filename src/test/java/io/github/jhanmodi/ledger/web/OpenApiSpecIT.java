package io.github.jhanmodi.ledger.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jhanmodi.ledger.TestcontainersConfiguration;
import io.github.jhanmodi.ledger.web.ApiExceptionHandler.InvalidField;
import io.github.jhanmodi.ledger.web.ApiJson.AccountResponse;
import io.github.jhanmodi.ledger.web.ApiJson.AmountRequest;
import io.github.jhanmodi.ledger.web.ApiJson.BalanceJson;
import io.github.jhanmodi.ledger.web.ApiJson.EntryJson;
import io.github.jhanmodi.ledger.web.ApiJson.EntryPageJson;
import io.github.jhanmodi.ledger.web.ApiJson.FundingRequest;
import io.github.jhanmodi.ledger.web.ApiJson.FundingResponse;
import io.github.jhanmodi.ledger.web.ApiJson.MoneyJson;
import io.github.jhanmodi.ledger.web.ApiJson.OpenAccountRequest;
import io.github.jhanmodi.ledger.web.ApiJson.TransferRequest;
import io.github.jhanmodi.ledger.web.ApiJson.TransferResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Keeps {@code docs/openapi.yaml} true to the code (ADR-0024). The spec is written by hand, so nothing generates it
 * from the controllers. This test compares it with what Spring actually serves, and fails on any difference it can see:
 *
 * <ul>
 *   <li>the endpoints: method and path, including the names of path variables;
 *   <li>each endpoint's headers and parameters: names, whether they're required, their types, and their limits;
 *   <li>which JSON shape each endpoint takes and returns;
 *   <li>each shape's fields: names, types, which are required, enum values, and length limits;
 *   <li>that request shapes refuse unknown fields, as the API does (ADR-0021);
 *   <li>that every {@code $ref} points at something.
 * </ul>
 *
 * <p>It can't see status codes, problem types, or descriptions. Those are checked by review against the API tests.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiSpecIT {

    private static final Path SPEC = Path.of("docs", "openapi.yaml");

    /** Only the client API is documented. Spring's own {@code /error} and Actuator's health check are not. */
    private static final String API_PREFIX = "/v1/";

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    /** Which schema in the spec documents which JSON record. */
    private static final Map<String, Class<?>> RECORD_SCHEMAS = Map.ofEntries(
            Map.entry("Money", MoneyJson.class),
            Map.entry("AmountRequest", AmountRequest.class),
            Map.entry("OpenAccountRequest", OpenAccountRequest.class),
            Map.entry("Account", AccountResponse.class),
            Map.entry("Balance", BalanceJson.class),
            Map.entry("Entry", EntryJson.class),
            Map.entry("EntryPage", EntryPageJson.class),
            Map.entry("TransferRequest", TransferRequest.class),
            Map.entry("Transfer", TransferResponse.class),
            Map.entry("FundingRequest", FundingRequest.class),
            Map.entry("Funding", FundingResponse.class),
            Map.entry("InvalidField", InvalidField.class));

    /** Schemas that aren't a record: the currency enum, and the Problem Details shapes Spring's ProblemDetail builds. */
    private static final Set<String> OTHER_SCHEMAS = Set.of("Currency", "Problem", "InvalidRequestProblem");

    /** How a Java type appears in JSON Schema. A null format means the spec must not give one. */
    private record JsonType(String type, @Nullable String format) {}

    private static final Map<Class<?>, JsonType> JSON_TYPES = Map.of(
            UUID.class, new JsonType("string", "uuid"),
            String.class, new JsonType("string", null),
            long.class, new JsonType("integer", "int64"),
            Long.class, new JsonType("integer", "int64"),
            int.class, new JsonType("integer", "int32"),
            Instant.class, new JsonType("string", "date-time"),
            LocalDate.class, new JsonType("string", "date"));

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    Map<String, Object> spec;

    @BeforeEach
    void readSpec() throws IOException {
        // SafeConstructor builds only plain maps, lists, and scalars, never arbitrary Java objects.
        try (Reader reader = Files.newBufferedReader(SPEC)) {
            spec = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        }
    }

    @Test
    void documentsExactlyTheEndpointsTheApiServes() {
        assertThat(documentedEndpoints().keySet())
                .containsExactlyInAnyOrderElementsOf(servedEndpoints().keySet());
    }

    @Test
    void documentsEachEndpointsHeadersAndParametersWithTheCodesRules() {
        Map<String, Map<String, Object>> documented = documentedEndpoints();
        servedEndpoints().forEach((endpoint, handler) -> {
            assertThat(documented.keySet()).as("documented endpoints").contains(endpoint);
            Map<String, Map<String, Object>> documentedParameters = documentedParameters(documented.get(endpoint));
            Map<String, MethodParameter> servedParameters = servedParameters(handler);

            assertThat(documentedParameters.keySet())
                    .as(endpoint + " parameters")
                    .containsExactlyInAnyOrderElementsOf(servedParameters.keySet());
            servedParameters.forEach((parameter, served) ->
                    assertParameterMatches(endpoint + " " + parameter, documentedParameters.get(parameter), served));
        });
    }

    @Test
    void documentsWhichJsonShapeEachEndpointTakesAndReturns() {
        Map<String, Map<String, Object>> documented = documentedEndpoints();
        servedEndpoints().forEach((endpoint, handler) -> {
            assertThat(documented.keySet()).as("documented endpoints").contains(endpoint);
            Map<String, Object> operation = documented.get(endpoint);

            assertThat(requestSchemaName(operation))
                    .as(endpoint + " request body")
                    .isEqualTo(schemaNameOf(requestBodyType(handler)));
            assertThat(successSchemaName(operation))
                    .as(endpoint + " success response body")
                    .isEqualTo(schemaNameOf(responseBodyType(handler)));
        });
    }

    @Test
    void eachSchemaHasExactlyItsRecordsFields() {
        RECORD_SCHEMAS.forEach((name, type) -> {
            Map<String, Object> schema = map(schemas().get(name));
            Map<String, Object> properties = map(schema.get("properties"));
            RecordComponent[] components = type.getRecordComponents();

            assertThat(properties.keySet())
                    .as(name + " fields")
                    .containsExactlyInAnyOrderElementsOf(Arrays.stream(components)
                            .map(RecordComponent::getName)
                            .toList());
            assertThat(list(schema.get("required")))
                    .as(name + " required fields")
                    .containsExactlyInAnyOrderElementsOf(requiredFields(type));
            if (isRequest(type)) {
                assertThat(schema.get("additionalProperties"))
                        .as(name + " refuses unknown fields")
                        .isEqualTo(false);
            }
            for (RecordComponent component : components) {
                assertFieldMatches(
                        name + "." + component.getName(), map(properties.get(component.getName())), component);
            }
        });
    }

    @Test
    void everyJsonRecordHasASchemaAndEverySchemaIsAccountedFor() {
        Set<Class<?>> records = new HashSet<>();
        Arrays.stream(ApiJson.class.getDeclaredClasses())
                .filter(Class::isRecord)
                .forEach(records::add);
        records.add(InvalidField.class);
        Set<String> accountedFor = new HashSet<>(RECORD_SCHEMAS.keySet());
        accountedFor.addAll(OTHER_SCHEMAS);

        assertThat(RECORD_SCHEMAS.values()).containsExactlyInAnyOrderElementsOf(records);
        assertThat(schemas().keySet()).containsExactlyInAnyOrderElementsOf(accountedFor);
    }

    @Test
    void everyReferenceResolves() {
        List<String> references = new ArrayList<>();
        collectReferences(spec, references);

        assertThat(references)
                .isNotEmpty()
                .allSatisfy(
                        reference -> assertThat(lookUp(reference)).as(reference).isNotNull());
    }

    // --- what Spring serves ---

    /** Each API endpoint, as {@code "POST /v1/transfers"}, and the controller method that handles it. */
    private Map<String, HandlerMethod> servedEndpoints() {
        Map<String, HandlerMethod> endpoints = new TreeMap<>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            for (String path : info.getPatternValues()) {
                if (path.startsWith(API_PREFIX)) {
                    Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
                    assertThat(methods).as(path + " names its HTTP method").isNotEmpty();
                    methods.forEach(method -> endpoints.put(method + " " + path, handler));
                }
            }
        });
        return endpoints;
    }

    /** The method's headers, path variables, and query parameters, as {@code "header Idempotency-Key"}. */
    private static Map<String, MethodParameter> servedParameters(HandlerMethod handler) {
        Map<String, MethodParameter> parameters = new TreeMap<>();
        for (MethodParameter original : handler.getMethodParameters()) {
            MethodParameter parameter = new MethodParameter(original);
            parameter.initParameterNameDiscovery(new DefaultParameterNameDiscoverer());
            PathVariable path = parameter.getParameterAnnotation(PathVariable.class);
            RequestParam query = parameter.getParameterAnnotation(RequestParam.class);
            RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
            if (path != null) {
                parameters.put("path " + nameOf(path.name(), path.value(), parameter), parameter);
            } else if (query != null) {
                parameters.put("query " + nameOf(query.name(), query.value(), parameter), parameter);
            } else if (header != null) {
                parameters.put("header " + nameOf(header.name(), header.value(), parameter), parameter);
            }
        }
        return parameters;
    }

    /** The name the client uses: the annotation's, or else the Java parameter's. */
    private static String nameOf(String name, String value, MethodParameter parameter) {
        if (!name.isEmpty()) {
            return name;
        }
        return value.isEmpty() ? parameter.getParameterName() : value;
    }

    private static boolean isRequired(MethodParameter parameter) {
        if (parameter.hasParameterAnnotation(PathVariable.class)) {
            return true;
        }
        RequestParam query = parameter.getParameterAnnotation(RequestParam.class);
        if (query != null) {
            return query.required() && ValueConstants.DEFAULT_NONE.equals(query.defaultValue());
        }
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        return header.required() && ValueConstants.DEFAULT_NONE.equals(header.defaultValue());
    }

    private static @Nullable Class<?> requestBodyType(HandlerMethod handler) {
        for (MethodParameter parameter : handler.getMethodParameters()) {
            if (parameter.hasParameterAnnotation(RequestBody.class)) {
                return parameter.getParameterType();
            }
        }
        return null;
    }

    /** The body type a controller method returns, looking inside {@code ResponseEntity<T>}. */
    private static Class<?> responseBodyType(HandlerMethod handler) {
        ResolvableType type = ResolvableType.forMethodReturnType(handler.getMethod());
        if (ResponseEntity.class.equals(type.toClass())) {
            type = type.getGeneric(0);
        }
        return type.toClass();
    }

    private static boolean isRequest(Class<?> record) {
        return record.getSimpleName().endsWith("Request");
    }

    /**
     * A request's required fields are the ones validation insists on. A response's are all of them: Jackson writes
     * every field, null or not.
     */
    private static List<String> requiredFields(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .filter(component -> !isRequest(record)
                        || field(component).isAnnotationPresent(NotNull.class)
                        || field(component).isAnnotationPresent(NotBlank.class))
                .map(RecordComponent::getName)
                .toList();
    }

    /** The record's private field, which carries the component's validation annotations. */
    private static Field field(RecordComponent component) {
        try {
            return component.getDeclaringRecord().getDeclaredField(component.getName());
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- what the spec documents ---

    /** Each documented operation, as {@code "POST /v1/transfers"}, with its path's parameters merged in. */
    private Map<String, Map<String, Object>> documentedEndpoints() {
        Map<String, Map<String, Object>> endpoints = new TreeMap<>();
        map(spec.get("paths")).forEach((path, item) -> {
            Map<String, Object> pathItem = map(item);
            List<Object> pathParameters = list(pathItem.get("parameters"));
            pathItem.forEach((method, value) -> {
                if (HTTP_METHODS.contains(method)) {
                    Map<String, Object> operation = new TreeMap<>(map(value));
                    List<Object> parameters = new ArrayList<>(pathParameters);
                    parameters.addAll(list(operation.get("parameters")));
                    operation.put("parameters", parameters);
                    endpoints.put(method.toUpperCase() + " " + path, operation);
                }
            });
        });
        return endpoints;
    }

    private Map<String, Map<String, Object>> documentedParameters(Map<String, Object> operation) {
        Map<String, Map<String, Object>> parameters = new TreeMap<>();
        for (Object entry : list(operation.get("parameters"))) {
            Map<String, Object> parameter = resolve(map(entry));
            parameters.put(parameter.get("in") + " " + parameter.get("name"), parameter);
        }
        return parameters;
    }

    private static @Nullable String requestSchemaName(Map<String, Object> operation) {
        Object requestBody = operation.get("requestBody");
        if (requestBody == null) {
            return null;
        }
        assertThat(map(requestBody).get("required"))
                .as("request body is required")
                .isEqualTo(true);
        return referencedName(jsonSchemaOf(map(requestBody)));
    }

    /** The schema of the operation's one 2xx response. */
    private static @Nullable String successSchemaName(Map<String, Object> operation) {
        List<Map<String, Object>> successes = map(operation.get("responses")).entrySet().stream()
                .filter(response -> response.getKey().startsWith("2"))
                .map(response -> map(response.getValue()))
                .toList();
        assertThat(successes).as("2xx responses").hasSize(1);
        return referencedName(jsonSchemaOf(successes.getFirst()));
    }

    private static Map<String, Object> jsonSchemaOf(Map<String, Object> bodyOrResponse) {
        return map(
                map(map(bodyOrResponse.get("content")).get("application/json")).get("schema"));
    }

    private Map<String, Object> schemas() {
        return map(map(spec.get("components")).get("schemas"));
    }

    // --- comparing the two ---

    private void assertParameterMatches(String label, Map<String, Object> documented, MethodParameter served) {
        Map<String, Object> schema = map(documented.get("schema"));
        Pattern pattern = served.getParameterAnnotation(Pattern.class);
        Min min = served.getParameterAnnotation(Min.class);
        Max max = served.getParameterAnnotation(Max.class);

        assertThat(Boolean.TRUE.equals(documented.get("required")))
                .as(label + " required")
                .isEqualTo(isRequired(served));
        assertJsonType(label, schema, served.getParameterType());
        // The spec's pattern must match the whole value, as Bean Validation's @Pattern does.
        assertThat(schema.get("pattern"))
                .as(label + " pattern")
                .isEqualTo(pattern == null ? null : "^" + pattern.regexp() + "$");
        assertThat(asLong(schema.get("minimum"))).as(label + " minimum").isEqualTo(min == null ? null : min.value());
        assertThat(asLong(schema.get("maximum"))).as(label + " maximum").isEqualTo(max == null ? null : max.value());
    }

    private void assertFieldMatches(String label, Map<String, Object> property, RecordComponent component) {
        Class<?> type = component.getType();
        if (type.isRecord()) {
            assertThat(referencedName(property)).as(label).isEqualTo(schemaNameOf(type));
        } else if (List.class.equals(type)) {
            Class<?> element = ResolvableType.forType(component.getGenericType())
                    .getGeneric(0)
                    .toClass();
            assertThat(typeOf(property)).as(label + " type").isEqualTo("array");
            assertThat(referencedName(map(property.get("items"))))
                    .as(label + " items")
                    .isEqualTo(schemaNameOf(element));
        } else {
            assertJsonType(label, resolve(property), type);
        }
        MaxCharacters maxCharacters = field(component).getAnnotation(MaxCharacters.class);
        assertThat(asLong(property.get("maxLength")))
                .as(label + " maxLength")
                .isEqualTo(maxCharacters == null ? null : (long) maxCharacters.value());
    }

    private static void assertJsonType(String label, Map<String, Object> schema, Class<?> javaType) {
        if (javaType.isEnum()) {
            assertThat(typeOf(schema)).as(label + " type").isEqualTo("string");
            assertThat(list(schema.get("enum")))
                    .as(label + " values")
                    .containsExactlyInAnyOrderElementsOf(Arrays.stream(javaType.getEnumConstants())
                            .map(constant -> ((Enum<?>) constant).name())
                            .toList());
            return;
        }
        JsonType expected = JSON_TYPES.get(javaType);
        assertThat(expected).as(label + ": no JSON type known for " + javaType).isNotNull();
        assertThat(typeOf(schema)).as(label + " type").isEqualTo(expected.type());
        assertThat(schema.get("format")).as(label + " format").isEqualTo(expected.format());
    }

    private static @Nullable String schemaNameOf(@Nullable Class<?> type) {
        if (type == null) {
            return null;
        }
        return RECORD_SCHEMAS.entrySet().stream()
                .filter(entry -> entry.getValue().equals(type))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No schema in RECORD_SCHEMAS for " + type.getName()));
    }

    // --- reading YAML ---

    /** A schema's type. A nullable field's type is a list such as {@code [string, "null"]}; this returns the other one. */
    private static Object typeOf(Map<String, Object> schema) {
        Object type = schema.get("type");
        if (type instanceof List<?> types) {
            return types.stream()
                    .filter(each -> !"null".equals(each))
                    .findFirst()
                    .orElse(null);
        }
        return type;
    }

    private static @Nullable String referencedName(Map<String, Object> schema) {
        Object reference = schema.get("$ref");
        return reference == null
                ? null
                : reference.toString().substring(reference.toString().lastIndexOf('/') + 1);
    }

    /** The object a {@code $ref} points to, or the object itself if it isn't a reference. */
    private Map<String, Object> resolve(Map<String, Object> maybeReference) {
        Object reference = maybeReference.get("$ref");
        return reference == null ? maybeReference : map(lookUp(reference.toString()));
    }

    /** Follows a local reference such as {@code #/components/schemas/Money}; null if nothing is there. */
    private @Nullable Object lookUp(String reference) {
        if (!reference.startsWith("#/")) {
            return null;
        }
        Object node = spec;
        for (String key : reference.substring(2).split("/")) {
            if (!(node instanceof Map<?, ?> object)) {
                return null;
            }
            node = object.get(key);
        }
        return node;
    }

    private static void collectReferences(Object node, List<String> references) {
        if (node instanceof Map<?, ?> object) {
            object.forEach((key, value) -> {
                if ("$ref".equals(key)) {
                    references.add(value.toString());
                } else {
                    collectReferences(value, references);
                }
            });
        } else if (node instanceof List<?> items) {
            items.forEach(item -> collectReferences(item, references));
        }
    }

    private static @Nullable Long asLong(@Nullable Object number) {
        return number == null ? null : ((Number) number).longValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(@Nullable Object node) {
        return node == null ? Map.of() : (Map<String, Object>) node;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(@Nullable Object node) {
        return node == null ? List.of() : (List<Object>) node;
    }
}
