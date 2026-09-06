package org.vrml.spec;

/**
 * The generator refusing to produce a spec file.
 *
 * <p>Every throw here is a decision the generator is not allowed to make on its own: an ambiguous
 * node name, a declaration in a shape the reader does not understand, a node that ended up with no
 * fields. Shipping a quietly guessed table would put those guesses into every user's completion
 * list, so the build stops instead.
 */
final class SpecGenException extends Exception {

    private static final long serialVersionUID = 1L;

    SpecGenException(String message) {
        super(message);
    }

    SpecGenException(String message, Throwable cause) {
        super(message, cause);
    }
}
