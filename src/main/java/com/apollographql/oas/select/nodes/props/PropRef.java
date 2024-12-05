package com.apollographql.oas.select.nodes.props;

import com.apollographql.oas.select.context.Context;
import com.apollographql.oas.select.factory.Factory;
import com.apollographql.oas.select.nodes.Obj;
import com.apollographql.oas.select.nodes.Type;
import io.swagger.v3.oas.models.media.Schema;

import java.io.IOException;
import java.io.Writer;

import static com.apollographql.oas.select.log.Trace.trace;

public class PropRef extends Prop {
  private final String ref;
  private Type refType;

  public PropRef(Type parent, String name, Schema schema, String ref) {
    super(parent, name, schema);
    this.ref = ref;
  }

  public String getRef() {
    return ref;
  }

  public Type getRefType() {
    return refType;
  }

  @Override
  public String getValue(Context context) {
    return getRefType().getSimpleName();
  }

  @Override
  public void visit(final Context context) {
    context.enter(this);
    trace(context, "-> [prop-ref]", "in " + getName() + ", ref: " + getRef());

    final Type cached = context.get(getRef());
    if (cached == null) {
      final Schema schema = context.lookupRef(getRef());
      assert schema != null;

      final Type type = Factory.fromSchema(context, this, schema);
      this.refType = type;
      assert type != null;

      type.setName(getRef());
      type.visit(context);
    }

    trace(context, "<- [prop-ref]", "out " + getName() + ", ref: " + getRef());
    context.leave(this);
  }

  @Override
  public void select(final Context context, final Writer writer) throws IOException {
    final String fieldName = getName().startsWith("@") ? getName().substring(1) : getName();
    writer
      .append(" ".repeat(context.getStack().size()))
      .append(fieldName);

    if (needsBrackets(getRefType())) {
      writer.append(" {");
      writer.append("\n");
    }

    for (Type child : getChildren()) {
      child.select(context, writer);
    }

    if (needsBrackets(getRefType())) {
      writer
        .append(" ".repeat(context.getStack().size()))
        .append("}");

    writer.append("\n");
    }
  }

  private boolean needsBrackets(Type child) {
    return child instanceof Obj;
  }

  @Override
  public String toString() {
    return "PropRef {" +
      "name='" + getName() + '\'' +
      ", ref='" + getRef() + '\'' +
      ", parent='" + getParent().getName() + '\'' +
      '}';
  }
}