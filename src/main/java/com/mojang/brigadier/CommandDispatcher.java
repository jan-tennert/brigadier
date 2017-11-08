package com.mojang.brigadier;

import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class CommandDispatcher<S> {
    public static final SimpleCommandExceptionType ERROR_UNKNOWN_COMMAND = new SimpleCommandExceptionType("command.unknown.command", "Unknown command");
    public static final SimpleCommandExceptionType ERROR_UNKNOWN_ARGUMENT = new SimpleCommandExceptionType("command.unknown.argument", "Incorrect argument for command");
    public static final SimpleCommandExceptionType ERROR_EXPECTED_ARGUMENT_SEPARATOR = new SimpleCommandExceptionType("command.expected.separator", "Expected whitespace to end one argument, but found trailing data");
    public static final SimpleCommandExceptionType ERROR_COMMAND_FAILED = new SimpleCommandExceptionType("command.failed", "Expected whitespace to end one argument, but found trailing data");

    public static final String ARGUMENT_SEPARATOR = " ";
    public static final char ARGUMENT_SEPARATOR_CHAR = ' ';
    private static final String USAGE_OPTIONAL_OPEN = "[";
    private static final String USAGE_OPTIONAL_CLOSE = "]";
    private static final String USAGE_REQUIRED_OPEN = "(";
    private static final String USAGE_REQUIRED_CLOSE = ")";
    private static final String USAGE_OR = "|";

    private final RootCommandNode<S> root;
    private final Predicate<CommandNode<S>> hasCommand = new Predicate<CommandNode<S>>() {
        @Override
        public boolean test(final CommandNode<S> input) {
            return input != null && (input.getCommand() != null || input.getChildren().stream().anyMatch(hasCommand));
        }
    };

    public CommandDispatcher(final RootCommandNode<S> root) {
        this.root = root;
    }

    public CommandDispatcher() {
        this(new RootCommandNode<>());
    }

    public LiteralCommandNode<S> register(final LiteralArgumentBuilder<S> command) {
        final LiteralCommandNode<S> build = command.build();
        root.addChild(build);
        return build;
    }

    public int execute(final String input, final S source) throws CommandSyntaxException {
        final ParseResults<S> parse = parse(input, source);
        return execute(parse);
    }

    public int execute(final ParseResults<S> parse) throws CommandSyntaxException {
        if (parse.getReader().canRead()) {
            if (parse.getExceptions().size() == 1) {
                throw parse.getExceptions().values().iterator().next();
            } else if (parse.getContext().getRange().isEmpty()) {
                throw ERROR_UNKNOWN_COMMAND.createWithContext(parse.getReader());
            } else {
                throw ERROR_UNKNOWN_ARGUMENT.createWithContext(parse.getReader());
            }
        }

        int result = 0;
        boolean foundCommand = false;
        final Deque<CommandContextBuilder<S>> contexts = new ArrayDeque<>();
        contexts.add(parse.getContext());

        while (!contexts.isEmpty()) {
            final CommandContextBuilder<S> context = contexts.removeLast();
            final CommandContextBuilder<S> child = context.getChild();
            if (child != null) {
                if (!child.getNodes().isEmpty()) {
                    final RedirectModifier<S> modifier = Iterators.getLast(context.getNodes().keySet().iterator()).getRedirectModifier();
                    final Collection<S> results = modifier.apply(context.build());
                    if (results.isEmpty()) {
                        return 0;
                    }
                    for (final S source : results) {
                        contexts.add(child.copy().withSource(source));
                    }
                }
            } else if (context.getCommand() != null) {
                foundCommand = true;
                result += context.getCommand().run(context.build());
            }
        }

        if (!foundCommand) {
            throw ERROR_UNKNOWN_COMMAND.createWithContext(parse.getReader());
        }

        return result;
    }

    public ParseResults<S> parse(final String command, final S source) {
        final StringReader reader = new StringReader(command);
        final CommandContextBuilder<S> context = new CommandContextBuilder<>(this, source, 0);
        return parseNodes(root, reader, context);
    }

    private static class PartialParse<S> {
        public final CommandContextBuilder<S> context;
        public final ParseResults<S> parse;

        private PartialParse(final CommandContextBuilder<S> context, final ParseResults<S> parse) {
            this.context = context;
            this.parse = parse;
        }
    }

    private ParseResults<S> parseNodes(final CommandNode<S> node, final StringReader originalReader, final CommandContextBuilder<S> contextSoFar) {
        final S source = contextSoFar.getSource();
        final Map<CommandNode<S>, CommandSyntaxException> errors = Maps.newLinkedHashMap();
        final List<PartialParse<S>> potentials = Lists.newArrayList();
        final int cursor = originalReader.getCursor();

        for (final CommandNode<S> child : node.getChildren()) {
            if (!child.canUse(source)) {
                continue;
            }
            final CommandContextBuilder<S> context = contextSoFar.copy();
            final StringReader reader = new StringReader(originalReader);
            try {
                child.parse(reader, context);
                if (reader.canRead()) {
                    if (reader.peek() != ARGUMENT_SEPARATOR_CHAR) {
                        throw ERROR_EXPECTED_ARGUMENT_SEPARATOR.createWithContext(reader);
                    }
                }
            } catch (final CommandSyntaxException ex) {
                errors.put(child, ex);
                reader.setCursor(cursor);
                continue;
            }

            context.withCommand(child.getCommand());
            if (reader.canRead(2)) {
                reader.skip();
                if (child.getRedirect() != null) {
                    final CommandContextBuilder<S> childContext = new CommandContextBuilder<>(this, source, reader.getCursor());
                    childContext.withNode(child.getRedirect(), new StringRange(reader.getCursor(), reader.getCursor()));
                    final ParseResults<S> parse = parseNodes(child.getRedirect(), reader, childContext);
                    context.withChild(parse.getContext());
                    return new ParseResults<>(context, parse.getReader(), parse.getExceptions());
                } else {
                    final ParseResults<S> parse = parseNodes(child, reader, context);
                    potentials.add(new PartialParse<>(context, parse));
                }
            } else {
                potentials.add(new PartialParse<>(context, new ParseResults<>(context, reader, Collections.emptyMap())));
            }
        }

        if (!potentials.isEmpty()) {
            final List<PartialParse<S>> sorted = Lists.newArrayList(potentials);
            sorted.sort((a, b) -> {
                if (!a.parse.getReader().canRead() && b.parse.getReader().canRead()) {
                    return -1;
                }
                if (a.parse.getReader().canRead() && !b.parse.getReader().canRead()) {
                    return 1;
                }
                if (a.parse.getExceptions().isEmpty() && !b.parse.getExceptions().isEmpty()) {
                    return -1;
                }
                if (!a.parse.getExceptions().isEmpty() && b.parse.getExceptions().isEmpty()) {
                    return 1;
                }
                return 0;
            });
            final PartialParse<S> likely = sorted.get(0);
            return likely.parse;
        }

        return new ParseResults<>(contextSoFar, originalReader, errors);
    }

    public String[] getAllUsage(final CommandNode<S> node, final S source, final boolean restricted) {
        final ArrayList<String> result = Lists.newArrayList();
        getAllUsage(node, source, result, "", restricted);
        return result.toArray(new String[result.size()]);
    }

    private void getAllUsage(final CommandNode<S> node, final S source, final ArrayList<String> result, final String prefix, final boolean restricted) {
        if (restricted && !node.canUse(source)) {
            return;
        }

        if (node.getCommand() != null) {
            result.add(prefix);
        }

        if (node.getRedirect() != null) {
            final String redirect = node.getRedirect() == root ? "..." : "-> " + node.getRedirect().getUsageText();
            result.add(prefix.isEmpty() ? node.getUsageText() + ARGUMENT_SEPARATOR + redirect : prefix + ARGUMENT_SEPARATOR + redirect);
        } else if (!node.getChildren().isEmpty()) {
            for (final CommandNode<S> child : node.getChildren()) {
                getAllUsage(child, source, result, prefix.isEmpty() ? child.getUsageText() : prefix + ARGUMENT_SEPARATOR + child.getUsageText(), restricted);
            }
        }
    }

    public Map<CommandNode<S>, String> getSmartUsage(final CommandNode<S> node, final S source) {
        final Map<CommandNode<S>, String> result = Maps.newLinkedHashMap();

        final boolean optional = node.getCommand() != null;
        for (final CommandNode<S> child : node.getChildren()) {
            final String usage = getSmartUsage(child, source, optional, false);
            if (usage != null) {
                result.put(child, usage);
            }
        }
        return result;
    }

    private String getSmartUsage(final CommandNode<S> node, final S source, final boolean optional, final boolean deep) {
        if (!node.canUse(source)) {
            return null;
        }

        final String self = optional ? USAGE_OPTIONAL_OPEN + node.getUsageText() + USAGE_OPTIONAL_CLOSE : node.getUsageText();
        final boolean childOptional = node.getCommand() != null;
        final String open = childOptional ? USAGE_OPTIONAL_OPEN : USAGE_REQUIRED_OPEN;
        final String close = childOptional ? USAGE_OPTIONAL_CLOSE : USAGE_REQUIRED_CLOSE;

        if (!deep) {
            if (node.getRedirect() != null) {
                final String redirect = node.getRedirect() == root ? "..." : "-> " + node.getRedirect().getUsageText();
                return self + ARGUMENT_SEPARATOR + redirect;
            } else {
                final Collection<CommandNode<S>> children = node.getChildren().stream().filter(c -> c.canUse(source)).collect(Collectors.toList());
                if (children.size() == 1) {
                    final String usage = getSmartUsage(children.iterator().next(), source, childOptional, childOptional);
                    if (usage != null) {
                        return self + ARGUMENT_SEPARATOR + usage;
                    }
                } else if (children.size() > 1) {
                    final Set<String> childUsage = Sets.newLinkedHashSet();
                    for (final CommandNode<S> child : children) {
                        final String usage = getSmartUsage(child, source, childOptional, true);
                        if (usage != null) {
                            childUsage.add(usage);
                        }
                    }
                    if (childUsage.size() == 1) {
                        final String usage = childUsage.iterator().next();
                        return self + ARGUMENT_SEPARATOR + (childOptional ? USAGE_OPTIONAL_OPEN + usage + USAGE_OPTIONAL_CLOSE : usage);
                    } else if (childUsage.size() > 1) {
                        final StringBuilder builder = new StringBuilder(open);
                        int count = 0;
                        for (final CommandNode<S> child : children) {
                            if (count > 0) {
                                builder.append(USAGE_OR);
                            }
                            builder.append(child.getUsageText());
                            count++;
                        }
                        if (count > 0) {
                            builder.append(close);
                            return self + ARGUMENT_SEPARATOR + builder.toString();
                        }
                    }
                }
            }
        }

        return self;
    }

    private Set<String> findSuggestions(final CommandNode<S> node, final StringReader reader, final CommandContextBuilder<S> contextBuilder, final Set<String> result) {
        if (node.getRedirect() != null) {
            return findSuggestions(node.getRedirect(), reader, contextBuilder, result);
        }
        final S source = contextBuilder.getSource();
        for (final CommandNode<S> child : node.getChildren()) {
            if (!child.canUse(source)) {
                continue;
            }
            final CommandContextBuilder<S> context = contextBuilder.copy();
            final int cursor = reader.getCursor();
            try {
                child.parse(reader, context);
                if (reader.canRead()) {
                    if (reader.peek() == ARGUMENT_SEPARATOR_CHAR) {
                        reader.skip();
                        return findSuggestions(child, reader, context, result);
                    }
                } else {
                    reader.setCursor(cursor);
                    child.listSuggestions(reader.getRemaining(), result, context);
                }
            } catch (final CommandSyntaxException e) {
                reader.setCursor(cursor);
                child.listSuggestions(reader.getRemaining(), result, context);
            }
        }

        return result;
    }

    public String[] getCompletionSuggestions(final String command, final S source) {
        final StringReader reader = new StringReader(command);
        final Set<String> nodes = findSuggestions(root, reader, new CommandContextBuilder<>(this, source, 0), Sets.newLinkedHashSet());

        return nodes.toArray(new String[nodes.size()]);
    }

    public RootCommandNode<S> getRoot() {
        return root;
    }
}
