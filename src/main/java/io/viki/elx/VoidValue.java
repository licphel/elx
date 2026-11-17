package io.viki.elx;

/**
 * Java representation of the ELX void sentinel. Unlike Java null (ELX null),
 * this singleton denotes an absent or omitted value and owns no heap resource.
 */
public enum VoidValue {
  INSTANCE
}
