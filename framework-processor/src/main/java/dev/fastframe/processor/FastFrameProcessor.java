package dev.fastframe.processor;

import dev.fastframe.core.annotation.Body;
import dev.fastframe.core.annotation.Required;
import dev.fastframe.core.annotation.Size;
import dev.fastframe.core.annotation.Min;
import dev.fastframe.core.annotation.Max;
import dev.fastframe.core.annotation.Controller;
import dev.fastframe.core.annotation.Delete;
import dev.fastframe.core.annotation.Get;
import dev.fastframe.core.annotation.Inject;
import dev.fastframe.core.annotation.PathParam;
import dev.fastframe.core.annotation.Post;
import dev.fastframe.core.annotation.Put;
import dev.fastframe.core.annotation.QueryParam;
import dev.fastframe.core.annotation.Singleton;
import dev.fastframe.core.http.Request;
import dev.fastframe.core.http.Response;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** MVP compile-time DI and route binding; intentionally limited to public concrete class beans. */
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedAnnotationTypes({
        "dev.fastframe.core.annotation.Controller", "dev.fastframe.core.annotation.Singleton",
        "dev.fastframe.core.annotation.Get", "dev.fastframe.core.annotation.Post",
        "dev.fastframe.core.annotation.Put", "dev.fastframe.core.annotation.Delete"})
public final class FastFrameProcessor extends AbstractProcessor {
    private static final String GENERATED = "dev.fastframe.generated.FastFrameRoutes";
    private final Map<String, Bean> beans = new LinkedHashMap<>();
    private boolean finished;
    private boolean invalid;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (finished || round.processingOver()) return false;
        // Annotations from application sources are collected in one compile round.
        for (Element el : round.getElementsAnnotatedWith(Controller.class)) addBean(el);
        for (Element el : round.getElementsAnnotatedWith(Singleton.class)) addBean(el);
        if (beans.isEmpty()) return false;
        finished = true;
        if (invalid) return true;
        List<Bean> sorted = new ArrayList<>(beans.values());
        sorted.sort(Comparator.comparing(b -> b.name));
        for (Bean bean : sorted) resolveConstructor(bean);
        if (invalid) return true;
        List<Bean> initialized = new ArrayList<>();
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (Bean bean : sorted) visit(bean, visiting, visited, initialized);
        if (invalid) return true;
        List<Route> routes = collectRoutes(sorted);
        if (invalid) return true;
        try {
            writeRoutes(initialized, routes);
        } catch (IOException ex) {
            fail(null, "Failed generating " + GENERATED + ": " + ex.getMessage());
        }
        return true;
    }

    private void addBean(Element element) {
        if (!(element instanceof TypeElement type)) {
            fail(element, "@Controller / @Singleton must annotate a type");
            return;
        }
        if (type.getKind() != ElementKind.CLASS || !type.getModifiers().contains(Modifier.PUBLIC)
                || type.getModifiers().contains(Modifier.ABSTRACT)
                || type.getNestingKind().isNested()) {
            fail(type, "Only public, concrete, top-level classes can be FastFrame beans");
            return;
        }
        String name = type.getQualifiedName().toString();
        beans.putIfAbsent(name, new Bean(name, type));
    }

    private void resolveConstructor(Bean bean) {
        List<ExecutableElement> constructors = ElementFilter.constructorsIn(bean.type.getEnclosedElements());
        List<ExecutableElement> marked = constructors.stream()
                .filter(c -> c.getAnnotation(Inject.class) != null).toList();
        if (marked.size() > 1) {
            fail(bean.type, "Multiple @Inject constructors: " + bean.name);
            return;
        }
        if (constructors.isEmpty()) return; // public implicit no-arg constructor
        if (marked.isEmpty() && constructors.size() != 1) {
            fail(bean.type, "Multiple constructors require exactly one @Inject: " + bean.name);
            return;
        }
        ExecutableElement ctor = marked.isEmpty() ? constructors.getFirst() : marked.getFirst();
        if (!ctor.getModifiers().contains(Modifier.PUBLIC)) {
            fail(ctor, "Bean constructor must be public (v0.3 limitation)");
            return;
        }
        bean.constructor = ctor;
        for (VariableElement param : ctor.getParameters()) {
            if (param.asType().getKind() != TypeKind.DECLARED) {
                fail(param, "Constructor dependencies must be annotated class beans");
                continue;
            }
            String name = ((TypeElement) ((DeclaredType) param.asType()).asElement()).getQualifiedName().toString();
            Bean dependency = beans.get(name);
            if (dependency == null) {
                fail(param, "Unsatisfied dependency " + name + " in " + bean.name + "; add @Singleton or @Controller");
            } else {
                bean.dependencies.add(dependency);
            }
        }
    }

    private void visit(Bean bean, Set<String> visiting, Set<String> visited, List<Bean> order) {
        if (visited.contains(bean.name)) return;
        if (!visiting.add(bean.name)) {
            fail(bean.type, "Circular singleton dependency involving " + bean.name);
            return;
        }
        for (Bean dependency : bean.dependencies) visit(dependency, visiting, visited, order);
        visiting.remove(bean.name);
        if (visited.add(bean.name)) order.add(bean);
    }

    private List<Route> collectRoutes(List<Bean> sorted) {
        List<Route> result = new ArrayList<>();
        Map<String, ExecutableElement> seen = new HashMap<>();
        for (Bean bean : sorted) {
            Controller controller = bean.type.getAnnotation(Controller.class);
            if (controller == null) continue;
            for (ExecutableElement method : ElementFilter.methodsIn(bean.type.getEnclosedElements())) {
                String verb = null;
                String path = null;
                int count = 0;
                if (method.getAnnotation(Get.class) != null) { verb = "GET"; path = method.getAnnotation(Get.class).value(); count++; }
                if (method.getAnnotation(Post.class) != null) { verb = "POST"; path = method.getAnnotation(Post.class).value(); count++; }
                if (method.getAnnotation(Put.class) != null) { verb = "PUT"; path = method.getAnnotation(Put.class).value(); count++; }
                if (method.getAnnotation(Delete.class) != null) { verb = "DELETE"; path = method.getAnnotation(Delete.class).value(); count++; }
                if (count == 0) continue;
                if (count > 1) { fail(method, "Exactly one HTTP route annotation per method"); continue; }
                if (!method.getModifiers().contains(Modifier.PUBLIC) || method.getModifiers().contains(Modifier.STATIC)) {
                    fail(method, "Route method must be a public instance method");
                    continue;
                }
                String fullPath = join(controller.value(), path, method);
                if (fullPath == null) continue;
                boolean async = validateReturnType(method);
                if (invalid) continue;
                List<String> bindings = new ArrayList<>();
                int bodies = 0;
                for (VariableElement param : method.getParameters()) {
                    if (param.getAnnotation(Body.class) != null) bodies++;
                    bindings.add(parameterExpression(param, fullPath));
                }
                if (bodies > 1) fail(method, "At most one @Body record is supported per route");
                String key = verb + " " + fullPath;
                ExecutableElement previous = seen.putIfAbsent(key, method);
                if (previous != null) fail(method, "Duplicate annotated route " + key);
                result.add(new Route(verb, fullPath, bean, method, async, bindings));
            }
        }
        result.sort(Comparator.comparing((Route r) -> r.verb).thenComparing(r -> r.path));
        return result;
    }

    private String join(String prefix, String suffix, Element source) {
        if (!prefix.isEmpty() && !prefix.startsWith("/")) { fail(source, "Controller path must start with /"); return null; }
        if (!suffix.startsWith("/")) { fail(source, "Method path must start with /"); return null; }
        String base = prefix.equals("/") || prefix.isEmpty() ? ""
                : (prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix);
        String path = base + (suffix.equals("/") ? "" : suffix);
        if (path.isEmpty()) path = "/";
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.contains("//") || path.contains("?") || path.contains("#")) {
            fail(source, "Invalid route path: " + path); return null;
        }
        // Defensive source generation: user-provided route strings are escaped separately.
        return path;
    }

    private boolean validateReturnType(ExecutableElement method) {
        TypeMirror returned = method.getReturnType();
        TypeElement response = processingEnv.getElementUtils().getTypeElement(Response.class.getCanonicalName());
        TypeElement stage = processingEnv.getElementUtils().getTypeElement("java.util.concurrent.CompletionStage");
        if (processingEnv.getTypeUtils().isSameType(returned, response.asType())) return false;
        TypeMirror targetStage = processingEnv.getTypeUtils().getDeclaredType(stage, response.asType());
        if (processingEnv.getTypeUtils().isAssignable(returned, targetStage)) return true;
        fail(method, "Route must return Response or CompletionStage<Response>");
        return false;
    }

    private String parameterExpression(VariableElement parameter, String path) {
        TypeMirror type = parameter.asType();
        PathParam fromPath = parameter.getAnnotation(PathParam.class);
        QueryParam fromQuery = parameter.getAnnotation(QueryParam.class);
        Body fromBody = parameter.getAnnotation(Body.class);
        if (fromBody != null) {
            if (fromPath != null || fromQuery != null) {
                fail(parameter, "@Body cannot be combined with route/query parameter annotations");
                return "null";
            }
            if (type.getKind() != TypeKind.DECLARED
                    || !(((DeclaredType) type).asElement() instanceof TypeElement record)
                    || record.getKind() != ElementKind.RECORD || record.getNestingKind().isNested()
                    || !record.getModifiers().contains(Modifier.PUBLIC)) {
                fail(parameter, "@Body requires a public top-level Java record");
                return "null";
            }
            if (!record.getTypeParameters().isEmpty()) {
                fail(parameter, "Generic @Body records are not supported");
                return "null";
            }
            validateBodyRecord(record);
            return "__body__";
        }
        if (fromPath != null && fromQuery != null) {
            fail(parameter, "Cannot mix @PathParam and @QueryParam"); return "null";
        }
        if (fromPath == null && fromQuery == null) {
            if (type.toString().equals(Request.class.getCanonicalName())) return "request";
            fail(parameter, "Use Request, @Body, @PathParam, or @QueryParam for route arguments"); return "null";
        }
        String name = fromPath != null ? fromPath.value() : fromQuery.value();
        if (name.isBlank()) { fail(parameter, "Parameter name must not be blank"); return "null"; }
        if (fromPath != null && !path.contains("{" + name + "}")) {
            fail(parameter, "No {" + name + "} placeholder in " + path); return "null";
        }
        String accessor = fromPath != null ? "request.routeParam(" + literal(name) + ")"
                : "request.query(" + literal(name) + ")";
        String typeName = type.toString();
        return switch (typeName) {
            case "java.lang.String" -> accessor;
            case "long" -> "Long.parseLong(" + accessor + ")";
            case "int" -> "Integer.parseInt(" + accessor + ")";
            default -> { fail(parameter, "Only String, int and long are supported bound parameter types"); yield "null"; }
        };
    }

    private void validateBodyRecord(TypeElement record) {
        for (RecordComponentElement component : record.getRecordComponents()) {
            String type = component.asType().toString();
            boolean string = type.equals("java.lang.String");
            boolean numeric = type.equals("int") || type.equals("long")
                    || type.equals("java.lang.Integer") || type.equals("java.lang.Long");
            boolean bool = type.equals("boolean") || type.equals("java.lang.Boolean");
            if (!string && !numeric && !bool) {
                fail(component, "@Body record supports String, int/Integer, long/Long, boolean/Boolean only");
            }
            Size size = component.getAnnotation(Size.class);
            Min min = component.getAnnotation(Min.class);
            Max max = component.getAnnotation(Max.class);
            if (size != null && (!string || size.min() < 0 || size.max() < size.min())) {
                fail(component, "@Size requires String with 0 <= min <= max");
            }
            if ((min != null || max != null) && !numeric) {
                fail(component, "@Min / @Max require an integer type");
            }
            if (min != null && max != null && min.value() > max.value()) {
                fail(component, "@Min must not exceed @Max");
            }
        }
    }

    private void emitBody(Writer w, String argument, VariableElement parameter) throws IOException {
        TypeElement record = (TypeElement) ((DeclaredType) parameter.asType()).asElement();
        String reader = "json" + argument;
        w.write("        final dev.fastframe.core.http.JsonBody " + reader + " = dev.fastframe.core.http.JsonBody.parse(request);\n");
        List<String> fields = new ArrayList<>();
        List<String> names = new ArrayList<>();
        int i = 0;
        for (RecordComponentElement component : record.getRecordComponents()) {
            String type = component.asType().toString();
            String field = component.getSimpleName().toString();
            String value = reader + "v" + i++;
            String key = literal(field);
            String expr;
            boolean required = component.getAnnotation(Required.class) != null
                    || type.equals("int") || type.equals("long") || type.equals("boolean");
            String flag = Boolean.toString(required);
            Size size = component.getAnnotation(Size.class);
            Min min = component.getAnnotation(Min.class);
            Max max = component.getAnnotation(Max.class);
            if (type.equals("java.lang.String")) {
                expr = reader + ".string(" + key + ", " + flag + ", "
                        + (size == null ? 0 : size.min()) + ", "
                        + (size == null ? 4096 : size.max()) + ")";
            } else if (type.equals("int") || type.equals("java.lang.Integer")) {
                expr = reader + ".integer(" + key + ", " + flag + ", "
                        + (min == null ? "Integer.MIN_VALUE" : min.value() + "L") + ", "
                        + (max == null ? "Integer.MAX_VALUE" : max.value() + "L") + ")";
                if (type.equals("int")) expr = "java.util.Objects.requireNonNullElse(" + expr + ", 0)";
            } else if (type.equals("long") || type.equals("java.lang.Long")) {
                expr = reader + ".longValue(" + key + ", " + flag + ", "
                        + (min == null ? "Long.MIN_VALUE" : min.value() + "L") + ", "
                        + (max == null ? "Long.MAX_VALUE" : max.value() + "L") + ")";
                if (type.equals("long")) expr = "java.util.Objects.requireNonNullElse(" + expr + ", 0L)";
            } else {
                expr = reader + ".bool(" + key + ", " + flag + ")";
                if (type.equals("boolean")) expr = "java.util.Objects.requireNonNullElse(" + expr + ", false)";
            }
            w.write("        final " + type + " " + value + " = " + expr + ";\n");
            fields.add(value);
            names.add(key);
        }
        w.write("        " + reader + ".finish(" + String.join(", ", names) + ");\n");
        w.write("        " + argument + " = new " + record.getQualifiedName() + "("
                + String.join(", ", fields) + ");\n");
    }

    private void writeRoutes(List<Bean> initializationOrder, List<Route> routes) throws IOException {
        JavaFileObject source = processingEnv.getFiler().createSourceFile(GENERATED);
        try (Writer w = source.openWriter()) {
            w.write("package dev.fastframe.generated;\n\n");
            w.write("/** Generated by FastFrameProcessor. Do not edit. */\n");
            w.write("public final class FastFrameRoutes {\n");
            w.write("  private FastFrameRoutes() {}\n");
            w.write("  public static void register(dev.fastframe.core.http.Router router) {\n");
            w.write("    java.util.Objects.requireNonNull(router, \"router\");\n");
            int i = 0;
            for (Bean b : initializationOrder) b.variable = "bean" + i++;
            for (Bean b : initializationOrder) {
                w.write("    final " + b.name + " " + b.variable + " = new " + b.name + "(");
                w.write(String.join(", ", b.dependencies.stream().map(dep -> dep.variable).toList()));
                w.write(");\n");
            }
            for (Route r : routes) {
                boolean hasBoundValues = r.arguments.stream().anyMatch(a -> !a.equals("request"));
                w.write("    router." + (r.async ? "addAsync" : "add") + "(" + literal(r.verb) + ", " + literal(r.path) + ", request -> {\n");
                if (hasBoundValues) {
                    // Generated locals avoid per-request argument arrays and boxing.
                    for (int n = 0; n < r.arguments.size(); n++) {
                        String type = r.method.getParameters().get(n).asType().toString();
                        w.write("      final " + type + " arg" + n + ";\n");
                    }
                    w.write("      try {\n");
                    for (int n = 0; n < r.arguments.size(); n++) {
                        if (r.arguments.get(n).equals("__body__")) {
                            emitBody(w, "arg" + n, r.method.getParameters().get(n));
                        } else {
                            w.write("        arg" + n + " = " + r.arguments.get(n) + ";\n");
                        }
                    }
                    w.write("      } catch (IllegalArgumentException ex) {\n");
                    // Only argument conversion is caught; controller failures propagate normally.
                    if (r.async) {
                        w.write("        return java.util.concurrent.CompletableFuture.completedFuture(dev.fastframe.core.http.Response.text(\"Bad Request\").status(400));\n");
                    } else {
                        w.write("        return dev.fastframe.core.http.Response.text(\"Bad Request\").status(400);\n");
                    }
                    w.write("      }\n");
                }
                List<String> args = new ArrayList<>();
                for (int n = 0; n < r.arguments.size(); n++) {
                    args.add(hasBoundValues ? "arg" + n : r.arguments.get(n));
                }
                w.write("      return " + r.bean.variable + "." + r.method.getSimpleName() + "(" + String.join(", ", args) + ");\n");
                w.write("    });\n");
            }
            w.write("  }\n}\n");
        }
    }

    private static String literal(String input) {
        return "\"" + input.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }

    private void fail(Element element, String message) {
        invalid = true;
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private static final class Bean {
        final String name;
        final TypeElement type;
        final List<Bean> dependencies = new ArrayList<>();
        ExecutableElement constructor;
        String variable;
        Bean(String name, TypeElement type) { this.name = name; this.type = type; }
    }

    private record Route(String verb, String path, Bean bean, ExecutableElement method,
                         boolean async, List<String> arguments) { }
}
