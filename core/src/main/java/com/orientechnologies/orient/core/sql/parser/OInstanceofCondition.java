/* Generated By:JJTree: Do not edit this line. OInstanceofCondition.java Version 4.3 */
/* JavaCCOptions:MULTI=true,NODE_USES_PARSER=false,VISITOR=true,TRACK_TOKENS=true,NODE_PREFIX=O,NODE_EXTENDS=,NODE_FACTORY=,SUPPORT_CLASS_VISIBILITY_PUBLIC=true */
package com.orientechnologies.orient.core.sql.parser;

import com.orientechnologies.orient.core.command.OCommandContext;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.sql.executor.OResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class OInstanceofCondition extends OBooleanExpression {

  protected OExpression left;
  protected OIdentifier right;
  protected String      rightString;

  public OInstanceofCondition(int id) {
    super(id);
  }

  public OInstanceofCondition(OrientSql p, int id) {
    super(p, id);
  }

  /**
   * Accept the visitor.
   **/
  public Object jjtAccept(OrientSqlVisitor visitor, Object data) {
    return visitor.visit(this, data);
  }

  @Override public boolean evaluate(OIdentifiable currentRecord, OCommandContext ctx) {
    throw new UnsupportedOperationException("TODO Implement IndexMatch!!!");//TODO
  }

  @Override public boolean evaluate(OResult currentRecord, OCommandContext ctx) {
    throw new UnsupportedOperationException("TODO Implement IndexMatch!!!");//TODO
  }

  public void toString(Map<Object, Object> params, StringBuilder builder) {
    left.toString(params, builder);
    builder.append(" instanceof ");
    if (right != null) {
      right.toString(params, builder);
    } else if (rightString != null) {
      builder.append(rightString);
    }
  }

  @Override public boolean supportsBasicCalculation() {
    return left.supportsBasicCalculation();
  }

  @Override protected int getNumberOfExternalCalculations() {
    if (!left.supportsBasicCalculation()) {
      return 1;
    }
    return 0;
  }

  @Override protected List<Object> getExternalCalculationConditions() {
    if (!left.supportsBasicCalculation()) {
      return (List) Collections.singletonList(left);
    }
    return Collections.EMPTY_LIST;
  }

  @Override public boolean needsAliases(Set<String> aliases) {
    if (left.needsAliases(aliases)) {
      return true;
    }
    return false;
  }

  @Override public OInstanceofCondition copy() {
    OInstanceofCondition result = new OInstanceofCondition(-1);
    result.left = left.copy();
    result.right = right == null ? null : right.copy();
    result.rightString = rightString;
    return result;
  }

  @Override public boolean equals(Object o) {
    if (this == o)
      return true;
    if (o == null || getClass() != o.getClass())
      return false;

    OInstanceofCondition that = (OInstanceofCondition) o;

    if (left != null ? !left.equals(that.left) : that.left != null)
      return false;
    if (right != null ? !right.equals(that.right) : that.right != null)
      return false;
    if (rightString != null ? !rightString.equals(that.rightString) : that.rightString != null)
      return false;

    return true;
  }

  @Override public int hashCode() {
    int result = left != null ? left.hashCode() : 0;
    result = 31 * result + (right != null ? right.hashCode() : 0);
    result = 31 * result + (rightString != null ? rightString.hashCode() : 0);
    return result;
  }
}
/* JavaCC - OriginalChecksum=0b5eb529744f307228faa6b26f0592dc (do not edit this line) */
