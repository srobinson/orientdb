/* Generated By:JJTree: Do not edit this line. OIdentifier.java Version 4.3 */
/* JavaCCOptions:MULTI=true,NODE_USES_PARSER=false,VISITOR=true,TRACK_TOKENS=true,NODE_PREFIX=O,NODE_EXTENDS=,NODE_FACTORY=,SUPPORT_CLASS_VISIBILITY_PUBLIC=true */
package com.orientechnologies.orient.core.sql.parser;

import java.util.Map;

public class OIdentifier extends SimpleNode {

  protected String  value;
  protected boolean quoted = false;

  /**
   * set to true by the query executor/optimizer for internally generated aliases for query optimization
   */
  protected boolean internalAlias = false;

  public OIdentifier(int id) {
    super(id);
  }

  public OIdentifier(OrientSql p, int id) {
    super(p, id);
  }

  /** Accept the visitor. **/
  public Object jjtAccept(OrientSqlVisitor visitor, Object data) {
    return visitor.visit(this, data);
  }

  /**
   * returns the value as is, with back-ticks quoted with backslash
   * @return
   */
  public String getValue() {
    return value;
  }

  /**
   * accepts a plain value. Back-ticks have to be quoted.
   * @param value
   */
  public void setValue(String value) {
    this.value = value;
  }

  /**
   * returns the plain string representation of this identifier, with quoting removed from back-ticks
   * @return
   */
  public String getStringValue(){
    if(value == null){
      return null;
    }
    return value.replaceAll("\\\\`", "`");
  }

  /**
   * returns the plain string representation of this identifier, with quoting removed from back-ticks
   * @return
   */
  public void setStringValue(String s){
    if(s == null){
      value = null;
    }else{
      value = s.replaceAll("`", "\\\\`");
    }
  }


  @Override
  public String toString(String prefix) {
    if (quoted) {
      return '`' + value + '`';
    }
    return value;
  }

  public String toString(){
    return toString("");
  }

  public void toString(Map<Object, Object> params, StringBuilder builder) {
    if (quoted) {
      builder.append('`' + value + '`');
    } else {
      builder.append(value);
    }
  }

  public void setQuoted(boolean quoted) {
    this.quoted = quoted;
  }

  public OIdentifier copy(){
    OIdentifier result = new OIdentifier(-1);
    result.value = value;
    result.quoted = quoted;
    return result;
  }

  @Override public boolean equals(Object o) {
    if (this == o)
      return true;
    if (o == null || getClass() != o.getClass())
      return false;

    OIdentifier that = (OIdentifier) o;

    if (quoted != that.quoted)
      return false;
    if (internalAlias != that.internalAlias)
      return false;
    if (value != null ? !value.equals(that.value) : that.value != null)
      return false;

    return true;
  }

  @Override public int hashCode() {
    int result = value != null ? value.hashCode() : 0;
    result = 31 * result + (quoted ? 1 : 0);
    result = 31 * result + (internalAlias ? 1 : 0);
    return result;
  }
}
/* JavaCC - OriginalChecksum=691a2eb5096f7b5e634b2ca8ac2ded3a (do not edit this line) */
