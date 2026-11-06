#!/usr/bin/env python3
"""Check the launcher's translation resources.

AGENTS.md requires a new or changed string in android/app/src/main/res/values/strings.xml to
reach every values-*/strings.xml in the same commit, and requires a launcher string to ship in
every language -- not only English and Russian. This enforces it, plus the two failures that are
invisible in an English build:

  * a locale missing a string name -- Android falls back to English silently, so a half-translated
    screen still looks translated;
  * a translation whose printf arguments (%s, %d, %ls, %%, ...) do not match the English ones --
    the screen shows "%d" or the wrong number.

It also rejects an empty translation where English has text, and an unescaped apostrophe (aapt2
fails on those). The same rules run as a unit test (TranslationResourceTest); this script keeps
them readable in the CI log and does not need a JVM.

strings.xml carries only <string> entries today (no <plurals>, no <string-array>, no xliff), so a
regex over <string> covers the file. Adding one of those would need this widened, or its contents
would stop being checked without anything saying so.

    python3 scripts/qa/android/check-translations.py

Exit status is 0 when every locale agrees with the base locale, 1 otherwise.
"""

import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET

BASE_LOCALE = "values"

# No space flag: in this game's text "+25% Firepower" is a percent sign followed by a word, and
# reading "% F" as a conversion would demand that every translation keep "% F". Same expression
# as scripts/language/translate_kit.py, extended with the positional form (%1$s) the launcher's
# own strings use.
PRINTF = re.compile(r"%((?:(\d+)\$)?[-+#0]*\d*(?:\.\d+)?(?:hh|h|ll|l|L|I64)?[sSdiuoxXcCfFeEgGp])")

STRING_TAG = re.compile(r"<string\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", re.DOTALL)

# Every language the launcher's picker offers, as res/ folder names.
EXPECTED_LOCALES = [
    "values", "values-ar", "values-b+isv", "values-de", "values-es", "values-fa",
    "values-fr", "values-ko", "values-pl", "values-pt-rBR", "values-ru", "values-uk",
    "values-zh",
]


def res_dir(repo_root):
    return os.path.join(repo_root, "android", "app", "src", "main", "res")


def locale_files(res):
    files = {}
    for name in sorted(os.listdir(res)):
        if not name.startswith("values"):
            continue
        path = os.path.join(res, name, "strings.xml")
        if os.path.isfile(path):
            files[name] = path
    return files


def read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def strings(path):
    """String name -> raw value, in file order."""
    found = {}
    for name, value in STRING_TAG.findall(read(path)):
        found[name] = value
    return found


def printf_arguments(value):
    """The printf arguments in a string, as a comparable signature.

    Positional arguments (%1$s) are meant to be reorderable -- that is why they exist, and Korean
    and Chinese legitimately move %2$s and %3$s to follow their own word order. So the signature is
    the *set* of argument numbers plus the conversion letters, not their order. Plain arguments
    have no number and keep their order, which is what makes dropping one detectable.
    """
    positional = []
    ordered = []
    for match in PRINTF.finditer(value):
        conversion, number = match.group(1), match.group(2)
        if number is None:
            ordered.append(conversion)
        else:
            positional.append((int(number), conversion))
    positional.sort()
    return (tuple(conversion for _, conversion in positional), tuple(ordered))


def check(res):
    errors = []
    files = locale_files(res)
    if BASE_LOCALE not in files:
        return ["%s/strings.xml not found" % BASE_LOCALE], {}

    for locale in EXPECTED_LOCALES:
        if locale not in files:
            errors.append("missing locale %s/strings.xml" % locale)

    # A malformed file is the failure aapt2 would report much later.
    for locale, path in files.items():
        try:
            ET.parse(path)
        except ET.ParseError as exc:
            errors.append("%s/strings.xml is not valid XML: %s" % (locale, exc))

    base = strings(files[BASE_LOCALE])
    if len(base) < 100:
        errors.append("the base locale only has %d strings; is it the right file?" % len(base))

    for locale, path in files.items():
        if locale == BASE_LOCALE:
            continue
        translated = strings(path)

        missing = sorted(set(base) - set(translated))
        if missing:
            errors.append("%s is missing %d string(s) that would silently fall back to English: %s"
                          % (locale, len(missing), ", ".join(missing[:8])))
        extra = sorted(set(translated) - set(base))
        if extra:
            errors.append("%s declares %d string(s) the base locale does not: %s"
                          % (locale, len(extra), ", ".join(extra[:8])))

        for name, value in translated.items():
            english = base.get(name)
            if english is None:
                continue
            if english.strip() and not value.strip():
                errors.append("%s/%s is empty where English has text" % (locale, name))
            expected = printf_arguments(english)
            if expected != ((), ()):
                actual = printf_arguments(value)
                if expected != actual:
                    errors.append("%s/%s changed its printf arguments: English %s, translation %s"
                                  % (locale, name, expected, actual))

    # An unescaped apostrophe is an aapt2 error, and it has to be escaped in every locale.
    for locale, path in files.items():
        for name, value in STRING_TAG.findall(read(path)):
            for index, char in enumerate(value):
                if char == "'" and (index == 0 or value[index - 1] != "\\"):
                    errors.append("%s/%s has an unescaped apostrophe (aapt2 rejects it): %s"
                                  % (locale, name, value))
                    break

    return errors, {locale: len(strings(path)) for locale, path in files.items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo-root",
                        default=os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                             "..", "..", ".."),
                        help="repository root (default: the one this script lives in)")
    args = parser.parse_args()

    res = res_dir(os.path.abspath(args.repo_root))
    if not os.path.isdir(res):
        print("ERROR: %s not found" % res, file=sys.stderr)
        return 1

    errors, counts = check(res)
    for locale in sorted(counts):
        print("  %-18s %4d strings" % (locale, counts[locale]))

    if errors:
        print("\n%d problem(s):" % len(errors), file=sys.stderr)
        for error in errors:
            print("  - %s" % error, file=sys.stderr)
        return 1

    print("\nEvery locale declares the same strings as the base locale, no translation is empty, "
          "and no printf argument differs.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
