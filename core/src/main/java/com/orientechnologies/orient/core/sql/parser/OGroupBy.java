/* Generated By:JJTree: Do not edit this line. OGroupBy.java Version 4.3 */
/* JavaCCOptions:MULTI=true,NODE_USES_PARSER=false,VISITOR=true,TRACK_TOKENS=true,NODE_PREFIX=O,NODE_EXTENDS=,NODE_FACTORY=,SUPPORT_CLASS_VISIBILITY_PUBLIC=true */
package com.orientechnologies.orient.core.sql.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class OGroupBy extends SimpleNode {

  protected List<OExpression> items = new ArrayList<OExpression>();

  public OGroupBy(int id) {
    super(id);
  }

  public OGroupBy(OrientSql p, int id) {
    super(p, id);
  }

  /**
   * Accept the visitor.
   **/
  public Object jjtAccept(OrientSqlVisitor visitor, Object data) {
    return visitor.visit(this, data);
  }

  public void toString(Map<Object, Object> params, StringBuilder builder) {
    builder.append("GROUP BY ");
    for (int i = 0; i < items.size(); i++) {
      if (i > 0) {
        builder.append(", ");
      }
      items.get(i).toString(params, builder);
    }
  }

  public List<OExpression> getItems() {
    return items;
  }

  public OGroupBy copy() {
    OGroupBy result = new OGroupBy(-1);
    result.items = items.stream().map(x -> x.copy()).collect(Collectors.toList());
    return result;
  }

  @Override public boolean equals(Object o) {
    if (this == o)
      return true;
    if (o == null || getClass() != o.getClass())
      return false;

    OGroupBy oGroupBy = (OGroupBy) o;

    if (items != null ? !items.equals(oGroupBy.items) : oGroupBy.items != null)
      return false;

    return true;
  }

  @Override public int hashCode() {
    return items != null ? items.hashCode() : 0;
  }
}
/* JavaCC - OriginalChecksum=4739190aa6c1a3533a89b76a15bd6fdf (do not edit this line) */
