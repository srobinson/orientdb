/* Generated By:JJTree: Do not edit this line. OBaseExpression.java Version 4.3 */
/* JavaCCOptions:MULTI=true,NODE_USES_PARSER=false,VISITOR=true,TRACK_TOKENS=true,NODE_PREFIX=O,NODE_EXTENDS=,NODE_FACTORY=,SUPPORT_CLASS_VISIBILITY_PUBLIC=true */
package com.orientechnologies.orient.core.sql.parser;

import com.orientechnologies.orient.core.command.OCommandContext;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.exception.OCommandExecutionException;
import com.orientechnologies.orient.core.serialization.serializer.OStringSerializerHelper;
import com.orientechnologies.orient.core.sql.executor.AggregationContext;
import com.orientechnologies.orient.core.sql.executor.OResult;

import java.util.Map;
import java.util.Set;

public class OBaseExpression extends OMathExpression {

  private static final Object UNSET           = new Object();
  private              Object inputFinalValue = UNSET;

  protected ONumber number;

  protected OBaseIdentifier identifier;

  protected OInputParameter inputParam;

  protected String string;

  OModifier modifier;

  public OBaseExpression(int id) {
    super(id);
  }

  public OBaseExpression(OrientSql p, int id) {
    super(p, id);
  }

  public OBaseExpression(OIdentifier identifier) {
    super(-1);
    this.identifier = new OBaseIdentifier(identifier);
  }

  /**
   * Accept the visitor.
   **/
  public Object jjtAccept(OrientSqlVisitor visitor, Object data) {
    return visitor.visit(this, data);
  }

  @Override public String toString() {
    return super.toString();
  }

  public void toString(Map<Object, Object> params, StringBuilder builder) {
    if (number != null) {
      number.toString(params, builder);
    } else if (identifier != null) {
      identifier.toString(params, builder);
    } else if (string != null) {
      builder.append(string);
    } else if (inputParam != null) {
      inputParam.toString(params, builder);
    }

    if (modifier != null) {
      modifier.toString(params, builder);
    }

  }

  public Object execute(OIdentifiable iCurrentRecord, OCommandContext ctx) {
    Object result = null;
    if (number != null) {
      result = number.getValue();
    }
    if (identifier != null) {
      result = identifier.execute(iCurrentRecord, ctx);
    }
    if (string != null && string.length() > 1) {
      result = OStringSerializerHelper.decode(string.substring(1, string.length() - 1));
    }
    if (modifier != null) {
      result = modifier.execute(iCurrentRecord, result, ctx);
    }
    return result;
  }

  public Object execute(OResult iCurrentRecord, OCommandContext ctx) {
    Object result = null;
    if (number != null) {
      result = number.getValue();
    }
    if (identifier != null) {
      result = identifier.execute(iCurrentRecord, ctx);
    }
    if (string != null && string.length() > 1) {
      result = OStringSerializerHelper.decode(string.substring(1, string.length() - 1));
    }
    if (modifier != null) {
      result = modifier.execute(iCurrentRecord, result, ctx);
    }
    return result;
  }

  @Override protected boolean supportsBasicCalculation() {
    return true;
  }

  @Override public boolean isIndexedFunctionCall() {
    if (this.identifier == null) {
      return false;
    }
    return identifier.isIndexedFunctionCall();
  }

  public long estimateIndexedFunction(OFromClause target, OCommandContext context, OBinaryCompareOperator operator, Object right) {
    if (this.identifier == null) {
      return -1;
    }
    return identifier.estimateIndexedFunction(target, context, operator, right);
  }

  public Iterable<OIdentifiable> executeIndexedFunction(OFromClause target, OCommandContext context,
      OBinaryCompareOperator operator, Object right) {
    if (this.identifier == null) {
      return null;
    }
    return identifier.executeIndexedFunction(target, context, operator, right);
  }

  @Override public boolean isBaseIdentifier() {
    return identifier != null && modifier == null && identifier.isBaseIdentifier();
  }

  public boolean isEarlyCalculated() {
    if (number != null || inputParam != null || string != null) {
      return true;
    }
    if (identifier != null && identifier.isEarlyCalculated()) {
      return true;
    }
    return false;
  }

  @Override public boolean isExpand() {
    if (identifier != null) {
      return identifier.isExpand();
    }
    return false;
  }

  @Override public OExpression getExpandContent() {
    return this.identifier.getExpandContent();
  }

  public boolean needsAliases(Set<String> aliases) {
    if (this.identifier != null && this.identifier.needsAliases(aliases)) {
      return true;
    }
    if (modifier != null && modifier.needsAliases(aliases)) {
      return true;
    }
    return false;
  }

  @Override public boolean isAggregate() {
    if (identifier != null && identifier.isAggregate()) {
      return true;
    }
    return false;
  }

  public SimpleNode splitForAggregation(AggregateProjectionSplit aggregateProj) {
    if (isAggregate()) {
      SimpleNode splitResult = identifier.splitForAggregation(aggregateProj);
      if (splitResult instanceof OBaseIdentifier) {
        OBaseExpression result = new OBaseExpression(-1);
        result.identifier = (OBaseIdentifier) splitResult;
        return result;
      }
      return splitResult;
    } else {
      return this;
    }
  }

  public AggregationContext getAggregationContext(OCommandContext ctx) {
    if (identifier != null) {
      return identifier.getAggregationContext(ctx);
    } else {
      throw new OCommandExecutionException("cannot aggregate on " + toString());
    }
  }

  @Override public boolean equals(Object o) {
    if (this == o)
      return true;
    if (o == null || getClass() != o.getClass())
      return false;

    OBaseExpression that = (OBaseExpression) o;

    if (number != null ? !number.equals(that.number) : that.number != null)
      return false;
    if (identifier != null ? !identifier.equals(that.identifier) : that.identifier != null)
      return false;
    if (inputParam != null ? !inputParam.equals(that.inputParam) : that.inputParam != null)
      return false;
    if (string != null ? !string.equals(that.string) : that.string != null)
      return false;
    if (modifier != null ? !modifier.equals(that.modifier) : that.modifier != null)
      return false;

    return true;
  }

  @Override public int hashCode() {
    int result = number != null ? number.hashCode() : 0;
    result = 31 * result + (identifier != null ? identifier.hashCode() : 0);
    result = 31 * result + (inputParam != null ? inputParam.hashCode() : 0);
    result = 31 * result + (string != null ? string.hashCode() : 0);
    result = 31 * result + (modifier != null ? modifier.hashCode() : 0);
    return result;
  }
}

/* JavaCC - OriginalChecksum=71b3e2d1b65c923dc7cfe11f9f449d2b (do not edit this line) */
