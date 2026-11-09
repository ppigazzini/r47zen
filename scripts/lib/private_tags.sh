#!/bin/bash

# The IDs of working notes no reader of this repo has: the rows, reviewers, and
# decisions of an audit or a plan kept outside the tree. A page, a comment, or a
# commit message that cites one rests on content only its author can see
# (android/docs/dev/10-writing.md, "Say what a private note's ID stands for").
# scripts/docs/run_docs_lint.sh (check 9, every tracked text file) and
# scripts/docs/check_commit_message.sh (a commit message) both source this file,
# so the two cannot disagree on a form.
#
# Limit: the shapes such IDs take, not a detector of private content. A note's
# other words pass, and so does an ID of a shape not listed here.

# Print the one PCRE that matches a private tracker ID: a family prefix, a dash,
# and a number; a reviewer's or report's name; a plan item in parentheses; or a
# numbered decision.
private_tag_re() {
    local families='KT|NAT|BLD|CI|TEST|SEC|SEAM|SCR|UP|DOC|G[1-5]|V2[A-Z]'
    printf '%s' "\\b(?:(?:${families})-[0-9]{1,3}|V2K-K[0-9]|REPORT-[0-9]+|V-(?:NAT|KT|CI|BLD))\\b"
    printf '%s' "|\\(P[0-9]{1,2}\\)|\\b[Dd]ecision [0-9]+\\b"
}
