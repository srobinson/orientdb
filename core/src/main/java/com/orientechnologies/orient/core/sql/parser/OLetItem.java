/* Generated By:JJTree: Do not edit this line. OLetItem.java Version 4.3 */
/* JavaCCOptions:MULTI=true,NODE_USES_PARSER=false,VISITOR=true,TRACK_TOKENS=true,NODE_PREFIX=O,NODE_EXTENDS=,NODE_FACTORY=,SUPPORT_CLASS_VISIBILITY_PUBLIC=true */
package com.orientechnologies.orient.core.sql.parser;

import java.util.Map;

public class OLetItem extends SimpleNode {

  OIdentifier varName;
  OExpression expression;
  OStatement  query;

  public OLetItem(int id) {
    super(id);
  }

  public OLetItem(OrientSql p, int id) {
    super(p, id);
  }

  /**
   * Accept the visitor.
   **/
  public Object jjtAccept(OrientSqlVisitor visitor, Object data) {
    return visitor.visit(this, data);
  }

  public void toString(Map<Object, Object> params, StringBuilder builder) {
    varName.toString(params, builder);
    builder.append(" = ");
    if (expression != null) {
      expression.toString(params, builder);
    } else if (query != null) {
      builder.append("(");
      query.toString(params, builder);
      builder.append(")");
    }
  }

  public OLetItem copy() {
    OLetItem result = new OLetItem(-1);
    result.varName = varName.copy();
    result.expression = expression == null ? null : expression.copy();
    result.query = query == null ? null : query.copy();
    return result;
  }

  @Override public boolean equals(Object o) {
    if (this == o)
      return true;
    if (o == null || getClass() != o.getClass())
      return false;

    OLetItem oLetItem = (OLetItem) o;

    if (varName != null ? !varName.equals(oLetItem.varName) : oLetItem.varName != null)
      return false;
    if (expression != null ? !expression.equals(oLetItem.expression) : oLetItem.expression != null)
      return false;
    if (query != null ? !query.equals(oLetItem.query) : oLetItem.query != null)
      return false;

    return true;
  }

  @Override public int hashCode() {
    int result = varName != null ? varName.hashCode() : 0;
    result = 31 * result + (expression != null ? expression.hashCode() : 0);
    result = 31 * result + (query != null ? query.hashCode() : 0);
    return result;
  }
}
/* JavaCC - OriginalChecksum=bb3cd298d79f50d72f6842e6d6ea4fb2 (do not edit this line) */
