/*
 * MIT License
 *
 * Copyright (c) 2026 i-net software GmbH
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package de.inetsoftware.mail.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small JSON reader/writer used to keep the provider free of implementation dependencies. */
final class Json {

    static Object parse( String text ) {
        Parser parser = new Parser( text );
        Object value = parser.value();
        parser.whitespace();
        if( parser.pos != text.length() ) {
            throw new IllegalArgumentException( "Unexpected JSON content at " + parser.pos );
        }
        return value;
    }

    @SuppressWarnings( "unchecked" )
    static Map<String, Object> object( String text ) {
        Object value = parse( text );
        if( !(value instanceof Map) ) {
            throw new IllegalArgumentException( "Expected a JSON object" );
        }
        return (Map<String, Object>)value;
    }

    static String stringify( Object value ) {
        StringBuilder result = new StringBuilder();
        write( result, value );
        return result.toString();
    }

    private static void write( StringBuilder out, Object value ) {
        if( value == null ) {
            out.append( "null" );
        } else if( value instanceof String ) {
            quote( out, (String)value );
        } else if( value instanceof Number || value instanceof Boolean ) {
            out.append( value );
        } else if( value instanceof Map ) {
            out.append( '{' );
            boolean comma = false;
            for( Map.Entry<?, ?> entry : ((Map<?, ?>)value).entrySet() ) {
                if( comma ) {
                    out.append( ',' );
                }
                quote( out, String.valueOf( entry.getKey() ) );
                out.append( ':' );
                write( out, entry.getValue() );
                comma = true;
            }
            out.append( '}' );
        } else if( value instanceof Iterable ) {
            out.append( '[' );
            boolean comma = false;
            for( Object item : (Iterable<?>)value ) {
                if( comma ) {
                    out.append( ',' );
                }
                write( out, item );
                comma = true;
            }
            out.append( ']' );
        } else {
            throw new IllegalArgumentException( "Cannot encode " + value.getClass().getName() );
        }
    }

    private static void quote( StringBuilder out, String value ) {
        out.append( '"' );
        for( int i = 0; i < value.length(); i++ ) {
            char ch = value.charAt( i );
            switch( ch ) {
                case '"': out.append( "\\\"" ); break;
                case '\\': out.append( "\\\\" ); break;
                case '\b': out.append( "\\b" ); break;
                case '\f': out.append( "\\f" ); break;
                case '\n': out.append( "\\n" ); break;
                case '\r': out.append( "\\r" ); break;
                case '\t': out.append( "\\t" ); break;
                default:
                    if( ch < 0x20 ) {
                        out.append( String.format( "\\u%04x", (int)ch ) );
                    } else {
                        out.append( ch );
                    }
            }
        }
        out.append( '"' );
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser( String text ) {
            this.text = text;
        }

        Object value() {
            whitespace();
            if( pos >= text.length() ) {
                throw error( "Expected a value" );
            }
            switch( text.charAt( pos ) ) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': literal( "true" ); return Boolean.TRUE;
                case 'f': literal( "false" ); return Boolean.FALSE;
                case 'n': literal( "null" ); return null;
                default: return number();
            }
        }

        private Map<String, Object> object() {
            Map<String, Object> result = new LinkedHashMap<>();
            pos++;
            whitespace();
            if( take( '}' ) ) {
                return result;
            }
            do {
                whitespace();
                if( pos >= text.length() || text.charAt( pos ) != '"' ) {
                    throw error( "Expected an object key" );
                }
                String key = string();
                whitespace();
                expect( ':' );
                result.put( key, value() );
                whitespace();
            } while( take( ',' ) );
            expect( '}' );
            return result;
        }

        private List<Object> array() {
            List<Object> result = new ArrayList<>();
            pos++;
            whitespace();
            if( take( ']' ) ) {
                return result;
            }
            do {
                result.add( value() );
                whitespace();
            } while( take( ',' ) );
            expect( ']' );
            return result;
        }

        private String string() {
            expect( '"' );
            StringBuilder result = new StringBuilder();
            while( pos < text.length() ) {
                char ch = text.charAt( pos++ );
                if( ch == '"' ) {
                    return result.toString();
                }
                if( ch != '\\' ) {
                    result.append( ch );
                    continue;
                }
                if( pos >= text.length() ) {
                    throw error( "Incomplete escape" );
                }
                ch = text.charAt( pos++ );
                switch( ch ) {
                    case '"': case '\\': case '/': result.append( ch ); break;
                    case 'b': result.append( '\b' ); break;
                    case 'f': result.append( '\f' ); break;
                    case 'n': result.append( '\n' ); break;
                    case 'r': result.append( '\r' ); break;
                    case 't': result.append( '\t' ); break;
                    case 'u':
                        if( pos + 4 > text.length() ) {
                            throw error( "Incomplete unicode escape" );
                        }
                        result.append( (char)Integer.parseInt( text.substring( pos, pos + 4 ), 16 ) );
                        pos += 4;
                        break;
                    default: throw error( "Unknown escape" );
                }
            }
            throw error( "Unterminated string" );
        }

        private Number number() {
            int start = pos;
            if( take( '-' ) ) {
                // optional sign
            }
            while( pos < text.length() && Character.isDigit( text.charAt( pos ) ) ) {
                pos++;
            }
            boolean decimal = false;
            if( take( '.' ) ) {
                decimal = true;
                while( pos < text.length() && Character.isDigit( text.charAt( pos ) ) ) {
                    pos++;
                }
            }
            if( pos < text.length() && (text.charAt( pos ) == 'e' || text.charAt( pos ) == 'E') ) {
                decimal = true;
                pos++;
                if( pos < text.length() && (text.charAt( pos ) == '+' || text.charAt( pos ) == '-') ) {
                    pos++;
                }
                while( pos < text.length() && Character.isDigit( text.charAt( pos ) ) ) {
                    pos++;
                }
            }
            if( start == pos ) {
                throw error( "Expected a number" );
            }
            String number = text.substring( start, pos );
            try {
                if( decimal ) {
                    return Double.valueOf( number );
                }
                return Long.valueOf( number );
            } catch( NumberFormatException ex ) {
                throw error( "Invalid number" );
            }
        }

        private void literal( String literal ) {
            if( !text.regionMatches( pos, literal, 0, literal.length() ) ) {
                throw error( "Expected " + literal );
            }
            pos += literal.length();
        }

        private boolean take( char ch ) {
            if( pos < text.length() && text.charAt( pos ) == ch ) {
                pos++;
                return true;
            }
            return false;
        }

        private void expect( char ch ) {
            if( !take( ch ) ) {
                throw error( "Expected '" + ch + "'" );
            }
        }

        private void whitespace() {
            while( pos < text.length() && Character.isWhitespace( text.charAt( pos ) ) ) {
                pos++;
            }
        }

        private IllegalArgumentException error( String message ) {
            return new IllegalArgumentException( message + " at " + pos );
        }
    }

    private Json() {
    }
}
