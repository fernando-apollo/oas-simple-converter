package com.apollographql.oas.select.nodes;

import com.apollographql.oas.converter.utils.NameUtils;
import com.apollographql.oas.select.context.Context;
import com.apollographql.oas.select.factory.Factory;
import com.apollographql.oas.select.nodes.props.Prop;
import com.apollographql.oas.select.prompt.Prompt;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;

import java.io.IOException;
import java.io.Writer;
import java.util.*;
import java.util.stream.Collectors;

import static com.apollographql.oas.select.log.Trace.*;

public class Composed extends Type {
  private final Schema schema;

  public Composed(final Type parent, final String name, final Schema schema) {
    super(parent, name);
    this.schema = schema;
  }

  public Schema getSchema() {
    return schema;
  }

  @Override
  public String id() {
    final Schema schema = getSchema();
    final String refs = getChildren().stream().map(Type::id).collect(Collectors.joining(" + "));

    if (schema.getAllOf() != null) {
      return "comp:all-of://" + refs;
    }
    else if (schema.getOneOf() != null) {
      return "comp:one-of://" + refs;
    }

    return "comp://" + refs;
  }

  @Override
  public Set<Type> dependencies(final Context context) {
    if (!isVisited()) throw new IllegalStateException("Type should have been visited before asking for dependencies!");

    final Set<Type> set = new HashSet<>();

    for (Type p : getProps().values()) {
      set.addAll(p.dependencies(context));
    }

    return set;
  }

  @Override
  public void generate(final Context context, final Writer writer) throws IOException {
    context.enter(this);
    trace(context, "-> [comp::generate]", String.format("-> in: %s", this.getName()));

    if (getSchema().getOneOf() != null) {
      getChildren().get(0).generate(context, writer);
    }
    else if (getSchema().getAllOf() != null) {
      if (!getProps().isEmpty()) {
        writer.append("type ")
          .append(NameUtils.getRefName(getName()))
          .append(" {\n");

        for (Prop prop : this.getProps().values()) {
          trace(context, "-> [comp::generate]", String.format("-> property: %s (parent: %s)", prop.getName(), prop.getParent().getSimpleName()));
          prop.generate(context, writer);
        }

        writer.append("}\n\n");
      }
    }

    trace(context, "<- [comp::generate]", String.format("-> out: %s", this.getName()));
    context.leave(this);
  }

  @Override
  public void select(final Context context, final Writer writer) throws IOException {
    if (context.getStack().contains(this)) {
      warn(context, "[comp::select]", "Possible recursion! Stack should not already contain " + this);
      return;
    }
    context.enter(this);
    trace(context, "-> [comp::select]", String.format("-> in: %s", this.getSimpleName()));

    final Schema schema = getSchema();

    if (schema.getAllOf() != null) {
      for (Prop prop : getProps().values()) {
        prop.select(context, writer);
      }
    }
    else if (schema.getOneOf() != null) {
      assert getChildren().size() == 1;
      getChildren().get(0).select(context, writer);
    }

    trace(context, "<- [comp::select]", String.format("-> out: %s", this.getSimpleName()));
    context.leave(this);
  }

  @Override
  public void visit(final Context context) {
    context.enter(this);
    trace(context, "-> [composed]", "in: " + (getName() == null ? "[object]" : getName()));

    if (!context.inComposeContext(this))
      print(null, "In composed schema: " + getName());

    final ComposedSchema schema = (ComposedSchema) getSchema();
    if (schema.getAllOf() != null) {
      // this translates to a type with all the properties of the allOf schemas
      visitAllOfNode(context, schema);
    }
    else if (schema.getOneOf() != null) {
      // this translates to a Union type
      visitOneOfNode(context, schema);
    }
    else {
      throw new IllegalStateException("Composed.visit: unsupported composed schema: " + schema.getClass().getSimpleName());
    }

    setVisited(true);

    trace(context, "<- [composed]", "out: " + getName());
    context.leave(this);
  }

  /* we are collecting all nodes to combine them into a single object -- therefore we must 'silence' the prompt for
   * now until all types are collected and we can retrieve all the properties.  */
  private void visitAllOfNode(final Context context, final ComposedSchema schema) {
    final List<Schema> allOfs = schema.getAllOf();
    final List<String> refs = allOfs.stream().map(Schema::get$ref).toList();

    trace(context, "-> [composed::all-of]", "in: " + String.format("'%s' of: %d - refs: %s", name, allOfs.size(), refs));

    final Map<String, Prop> collected = new LinkedHashMap<>();
    for (int i = 0; i < allOfs.size(); i++) {
      final Schema allOfItemSchema = allOfs.get(i);

      final Type type = Factory.fromSchema(context, this, allOfItemSchema);
      trace(context, "   [composed::all-of]", "allOf type: " + type);
      assert type != null;

      // we are visiting all the tree -- then we'll let them choose which properties they want to add
      type.visit(context);
      collected.putAll(type.getProps());
    }

    final boolean inCompose = context.inComposeContext(this);
    if (inCompose) {
      getProps().putAll(collected);
    }
    else {
      promptPropertySelection(context, collected);
    }

    /*final List<Prop> dependencies = getProps().values().stream()
      .filter(p -> {
        trace(context, "-> [composed]", "visitProperties inCompose " + context.inComposeContext(this) + ", in " + id());
        return p instanceof PropRef || p instanceof PropArray;
      })
      .toList();

    for (final Prop dependency : dependencies) {
      if (!dependency.isVisited()) {
        trace(context, "-> [composed]", "adding prop dependency: " + dependency.getName());
        context.addPending(dependency);
      }
    }*/

    // we'll store it first, it might avoid recursion
    trace(context, "-> [composed]", "storing: " + getName() + " with: " + this);
    context.store(getName(), this);

    trace(context, "<- [composed::all-of]", "out: " + String.format("'%s' of: %d - refs: %s", name, allOfs.size(), refs));
  }

  private void promptPropertySelection(final Context context, final Map<String, Prop> properties) {
    final String propertiesNames = String.join(",\n - ",
      properties.values().stream().map(Type::getName).toList());

    final char addAll = Prompt
      .get()
      .yesNoSelect(" -> Add all properties from " + getName() + "?: \n - " + propertiesNames + "\n");

    /* we should only prompt for properties if:
     * 1. we are NOT a comp://all-of
     * 2. the comp://all-of contains our name (i.e: #/component/schemas/Extensible
     */
    if ((addAll == 'y' || addAll == 's')) {
      for (final Map.Entry<String, Prop> entry : properties.entrySet()) {
        final Prop prop = entry.getValue();
        if (addAll == 'y' || Prompt.get().yesNo("Add field '" + prop.forPrompt(context) + "'?")) {
          trace(context, "   [composed::props]", "prop: " + prop);

          // add property to our dependencies
          getProps().put(prop.getName(), prop);

          if (!this.getChildren().contains(prop)) {
            this.add(prop);
          }
        }
      }
    }
  }

  private void visitOneOfNode(final Context context, final ComposedSchema schema) {
    final var oneOfs = schema.getOneOf();
    trace(context, "-> [composed::one-of]", "in: " + String.format("OneOf %s with size: %d", name, oneOfs.size()));

    final Type result = Factory.fromUnion(context, this, oneOfs);
    assert result != null;
    result.visit(context);

//    final boolean inCompose = context.inComposeContext(this);
//    if (inCompose) {
//      getProps().putAll(result.getProps());
//    }
//    else {
//      promptPropertySelection(context, result.getProps());
//    }

    trace(context, "-> [composed::one-of]", "storing: " + getName() + " with: " + this);
    if (getName() != null)
      context.store(getName(), this);

    trace(context, "<- [composed::one-of]", "out: " + String.format("OneOf %s with size: %d", name, oneOfs.size()));
  }

}
