#!/bin/bash

# The British spellings android/docs/dev/10-writing.md bans, and how a line of
# prose is read for them. scripts/docs/run_docs_lint.sh (check 8, every tracked
# text file) and scripts/docs/check_commit_message.sh (a commit message) both
# source this file, so the two cannot disagree on a word.
#
# Each entry is a PCRE, matched case-insensitively anywhere in a word, so a
# camelCase or UPPER_SNAKE identifier is held as well as prose. A form that is
# also American (analyses, cancellation, fulfilled, programmer) is kept out by
# the entry's shape or a lookahead.
#
# Limit: a list, not a dictionary. A British form it does not name passes.

BRITISH_SPELLINGS=(
    # -our
    'colour' 'behaviour' 'honour' 'favour' 'flavour' 'labour' 'neighbour'
    'humour' 'rumour' 'harbour' 'armour' 'vapour' 'odour' 'rigour' 'vigour'
    'endeavour'
    # -re, -ence, -ogue, -mme
    'centre' 'fibre' 'litre' 'metre' 'theatre' 'calibre'
    'licence' 'defence' 'offence' 'pretence'
    'catalogue' 'dialogue' 'analogue' 'programme(?!r)'
    # -yse
    'analys(?:ed|ing|er)' 'analyse\b' 'paralys(?:e\b|ed|ing)'
    'catalys(?:e\b|ed|ing)'
    # a doubled l before -ed, -ing, or -er
    '(?:cancel|label|model|travel|signal|marshal|channel|level|fuel|tunnel|total|funnel|dial)l(?:ed|ing|er)'
    'counsell' 'jewell' 'marvellous' 'quarrell'
    'fulfil(?!l)' 'enrol(?!l)' 'instalment'
    # -ise, -isation
    '(?:initial|normal|optim|serial|synchron|custom|summar|final|minim|maxim|util|categor|priorit|author|visual|stabil|standard|special|character|sanit|token|parameter|local|material|random|raster|quant|vector|amort|general|recogn|organ|real|apolog|critic|memor|emphas|capital|central|modern|harmon|neutral|digit|discret)is(?:e|ed|es|er|ers|ing|ation|ations|able)'
    'practis(?:e|ed|es|ing)'
    # single words
    'grey' 'whilst' 'amongst' 'amidst' 'towards' 'afterwards' 'backwards'
    'judgement' 'acknowledgement' 'artefact' 'ageing' 'sceptic' 'manoeuvr'
    'speciality' 'aluminium' 'cheque' 'learnt' 'spelt'
)

# Print the one PCRE that matches any entry.
british_spelling_re() {
    local IFS='|'
    printf '(?i)(?:%s)' "${BRITISH_SPELLINGS[*]}"
}

# Print stdin with every `code span` and "quoted span" removed: a quotation
# keeps the spelling of what it quotes. A span is read one line at a time, so a
# quotation that wraps onto a second line is not skipped: keep a quoted British
# form and its quotation marks on one line.
strip_cited_spans() {
    sed -E 's/`[^`]*`//g; s/"[^"]*"//g'
}
