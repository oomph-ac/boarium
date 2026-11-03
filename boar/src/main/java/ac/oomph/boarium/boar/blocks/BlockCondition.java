package ac.oomph.boarium.boar.blocks;

import java.util.List;
import java.util.Locale;

/**
 * A custom block permutation's condition, in the small part of Molang that Boar reads: q.block_state('name') or query.block_state('name'), ==, !=, !, &&, ||, parentheses,
 * and number, 'string', true and false literals. Anything else, such as another query, math, or a comparison of a string with a number, is unknown to Boar.
 * q.block_property is the older name of q.block_state: the client registers both with the same function (ExpressionNode::_initializeMolangQueries).
 * The client applies a permutation when the condition's value, cut to an integer, is not 0 (BlockDefinitionGroup::initializeBlockFromDefinition).
 */
final class BlockCondition {
    // Bounds the time and stack a hostile server's conditions take.
    static final int MAX_LENGTH = 1024;
    private static final int MAX_DEPTH = 64;
    // The last Molang version in which q.block_property resolves (the query's max version in ExpressionNode::_initializeMolangQueries)
    private static final int LAST_BLOCK_PROPERTY_VERSION = 9;
    // The first Molang version in which && is evaluated before ||
    private static final int AND_BEFORE_OR = 6;
    // The value of a condition, or part of one, that Boar cannot tell
    private static final Object UNKNOWN = new Object();

    private final Node root;
    // How many nodes the condition has, which is the work to test it once
    final int size;

    private BlockCondition(Node root, int size) {
        this.root = root;
        this.size = size;
    }

    /**
     * The condition, or null when Boar cannot read it.
     *
     * @param keys the block's property names, in the order of its states' values
     * @param molangVersion the block's molangVersion, which the client takes as 0 when it is missing, not an int, or not from 0 to 13
     */
    static BlockCondition parse(String text, List<String> keys, int molangVersion) {
        if (text.length() > MAX_LENGTH) {
            return null;
        }
        Parser parser = new Parser(text, keys, molangVersion <= LAST_BLOCK_PROPERTY_VERSION);
        try {
            Node root = parser.or(0);
            parser.skipSpaces();
            // Before AND_BEFORE_OR, the client groups a mix of && and || another way, which Boar does not read.
            if (parser.at < text.length() || parser.ands > 0 && parser.ors > 0 && molangVersion < AND_BEFORE_OR) {
                return null;
            }
            return new BlockCondition(root, parser.nodes);
        } catch (Unreadable e) {
            return null;
        }
    }

    // TRUE or FALSE when the condition holds or not for a state with these property values, or null when Boar cannot tell.
    Boolean test(Object[] state) {
        Object value = this.root.eval(state);
        return value instanceof Float number ? (int) (float) number != 0 : null;
    }

    private sealed interface Node {
        Object eval(Object[] state);
    }

    private record Literal(Object value) implements Node {
        @Override
        public Object eval(Object[] state) {
            return this.value;
        }
    }

    // A bool state is a byte of 0 or 1 to Boar and 0 or 1 to Molang, and an int state a number.
    private record BlockState(int property) implements Node {
        @Override
        public Object eval(Object[] state) {
            return switch (state[this.property]) {
                case Byte value -> (float) value;
                case Integer value -> (float) value;
                case String value -> value;
                default -> UNKNOWN;
            };
        }
    }

    private record Not(Node operand) implements Node {
        @Override
        public Object eval(Object[] state) {
            return this.operand.eval(state) instanceof Float number ? bool(number == 0) : UNKNOWN;
        }
    }

    private record Equals(Node left, Node right, boolean negated) implements Node {
        @Override
        public Object eval(Object[] state) {
            Object left = this.left.eval(state);
            Object right = this.right.eval(state);
            if (left instanceof Float a && right instanceof Float b) {
                return bool((a.floatValue() == b.floatValue()) != this.negated);
            }
            if (left instanceof String a && right instanceof String b) {
                return bool(a.equals(b) != this.negated);
            }
            return UNKNOWN;
        }
    }

    // && and ||, which the client stops at once the left side decides them
    private record Logic(Node left, Node right, boolean and) implements Node {
        @Override
        public Object eval(Object[] state) {
            Object left = this.left.eval(state);
            if (left instanceof Float number && (number != 0) != this.and) {
                return bool(!this.and);
            }
            Object right = this.right.eval(state);
            if (!(left instanceof Float) || !(right instanceof Float number)) {
                return UNKNOWN;
            }
            return bool(number != 0);
        }
    }

    private static Float bool(boolean value) {
        return value ? 1F : 0F;
    }

    private static final class Unreadable extends Exception {
        Unreadable() {
            super(null, null, false, false);
        }
    }

    // A recursive descent parser, where ! binds tightest, then == and !=, then &&, then ||, as in Molang.
    private static final class Parser {
        private final String text;
        private final List<String> keys;
        private final boolean blockProperty;
        private int at;
        private int nodes;
        private int ands;
        private int ors;

        Parser(String text, List<String> keys, boolean blockProperty) {
            this.text = text;
            this.keys = keys;
            this.blockProperty = blockProperty;
        }

        Node or(int depth) throws Unreadable {
            Node node = this.and(depth);
            while (this.take("||")) {
                this.ors++;
                node = this.count(new Logic(node, this.and(depth), false));
            }
            return node;
        }

        private Node and(int depth) throws Unreadable {
            Node node = this.equality(depth);
            while (this.take("&&")) {
                this.ands++;
                node = this.count(new Logic(node, this.equality(depth), true));
            }
            return node;
        }

        private Node equality(int depth) throws Unreadable {
            Node node = this.unary(depth);
            while (true) {
                if (this.take("==")) {
                    node = this.count(new Equals(node, this.unary(depth), false));
                } else if (this.take("!=")) {
                    node = this.count(new Equals(node, this.unary(depth), true));
                } else {
                    return node;
                }
            }
        }

        private Node unary(int depth) throws Unreadable {
            if (depth > MAX_DEPTH) {
                throw new Unreadable();
            }
            if (this.take("!")) {
                return this.count(new Not(this.unary(depth + 1)));
            }
            if (this.take("(")) {
                Node node = this.or(depth + 1);
                this.expect(")");
                return node;
            }
            this.skipSpaces();
            if (this.at >= this.text.length()) {
                throw new Unreadable();
            }
            char c = this.text.charAt(this.at);
            if (c == '\'') {
                return this.count(new Literal(this.string()));
            }
            if (c >= '0' && c <= '9') {
                return this.count(new Literal(this.number()));
            }
            String word = this.word();
            if (this.blockProperty && (word.equals("q.block_property") || word.equals("query.block_property"))) {
                word = "q.block_state";
            }
            switch (word) {
                case "true" -> {
                    return this.count(new Literal(1F));
                }
                case "false" -> {
                    return this.count(new Literal(0F));
                }
                case "q.block_state", "query.block_state" -> {
                    this.expect("(");
                    this.skipSpaces();
                    int property = this.keys.indexOf(this.string());
                    this.expect(")");
                    if (property < 0) {
                        throw new Unreadable();
                    }
                    return this.count(new BlockState(property));
                }
                default -> throw new Unreadable();
            }
        }

        private Node count(Node node) {
            this.nodes++;
            return node;
        }

        // Molang names ignore case.
        private String word() {
            int start = this.at;
            while (this.at < this.text.length() && (Character.isLetterOrDigit(this.text.charAt(this.at)) || this.text.charAt(this.at) == '_' || this.text.charAt(this.at) == '.')) {
                this.at++;
            }
            return this.text.substring(start, this.at).toLowerCase(Locale.ROOT);
        }

        private String string() throws Unreadable {
            if (this.at >= this.text.length() || this.text.charAt(this.at) != '\'') {
                throw new Unreadable();
            }
            int end = this.text.indexOf('\'', this.at + 1);
            if (end < 0) {
                throw new Unreadable();
            }
            String value = this.text.substring(this.at + 1, end);
            this.at = end + 1;
            return value;
        }

        private Float number() throws Unreadable {
            int start = this.at;
            while (this.at < this.text.length() && (Character.isDigit(this.text.charAt(this.at)) || this.text.charAt(this.at) == '.')) {
                this.at++;
            }
            try {
                return Float.parseFloat(this.text.substring(start, this.at));
            } catch (NumberFormatException e) {
                throw new Unreadable();
            }
        }

        private boolean take(String token) {
            this.skipSpaces();
            if (!this.text.startsWith(token, this.at)) {
                return false;
            }
            // A ! before = is !=.
            if (token.equals("!") && this.text.startsWith("=", this.at + 1)) {
                return false;
            }
            this.at += token.length();
            return true;
        }

        private void expect(String token) throws Unreadable {
            if (!this.take(token)) {
                throw new Unreadable();
            }
        }

        void skipSpaces() {
            while (this.at < this.text.length() && Character.isWhitespace(this.text.charAt(this.at))) {
                this.at++;
            }
        }
    }
}
