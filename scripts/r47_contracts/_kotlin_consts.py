"""Helpers for reading simple Kotlin `const val` expressions from source files."""

from __future__ import annotations

import ast
import re
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from pathlib import Path

_CONST_PATTERN = re.compile(
    r"(?:private\s+|internal\s+)?const val\s+(\w+)\s*=\s*(.+)",
)
_FLOAT_SUFFIX_PATTERN = re.compile(r"(?<=\d)f\b")


class _ConstExpressionEvaluator(ast.NodeVisitor):
    def __init__(self, values: dict[str, float]) -> None:
        self.values = values

    def visit_Expression(self, node: ast.Expression) -> float:
        return self.visit(node.body)

    def visit_Name(self, node: ast.Name) -> float:
        return self.values[node.id]

    def visit_Constant(self, node: ast.Constant) -> float:
        if not isinstance(node.value, int | float):
            message = f"Unsupported constant: {node.value!r}"
            raise TypeError(message)
        return float(node.value)

    def visit_UnaryOp(self, node: ast.UnaryOp) -> float:
        operand = self.visit(node.operand)
        if isinstance(node.op, ast.USub):
            return -operand
        if isinstance(node.op, ast.UAdd):
            return operand
        message = f"Unsupported unary op: {ast.dump(node)}"
        raise TypeError(message)

    def visit_BinOp(self, node: ast.BinOp) -> float:
        left = self.visit(node.left)
        right = self.visit(node.right)
        if isinstance(node.op, ast.Add):
            return left + right
        if isinstance(node.op, ast.Sub):
            return left - right
        if isinstance(node.op, ast.Mult):
            return left * right
        if isinstance(node.op, ast.Div):
            return left / right
        message = f"Unsupported binary op: {ast.dump(node)}"
        raise TypeError(message)

    def generic_visit(self, node: ast.AST) -> float:
        message = f"Unsupported expression: {ast.dump(node)}"
        raise TypeError(message)


def parse_kotlin_const_values(path: Path) -> dict[str, float]:
    """Parse simple Kotlin `const val` expressions into evaluated float values."""
    values: dict[str, float] = {}
    raw_lines = path.read_text(encoding="utf-8").splitlines()
    for expression_parts in _iter_const_expressions(raw_lines):
        joined = " ".join(expression_parts)
        match = _CONST_PATTERN.match(joined)
        if match is None:
            continue

        name, expression = match.groups()
        sanitized = _FLOAT_SUFFIX_PATTERN.sub("", expression)
        parsed = ast.parse(sanitized, mode="eval")
        try:
            values[name] = _ConstExpressionEvaluator(values).visit(parsed)
        except KeyError, TypeError:
            continue

    return values


def _iter_const_expressions(lines: list[str]) -> list[list[str]]:
    expressions: list[list[str]] = []
    index = 0

    while index < len(lines):
        line = lines[index].strip()
        if "const val " not in line:
            index += 1
            continue

        expression_parts = [line]
        while expression_parts[-1].rstrip().endswith("="):
            index += 1
            expression_parts.append(lines[index].strip())

        while index + 1 < len(lines):
            next_line = lines[index + 1]
            if _starts_new_const_block(next_line):
                break
            index += 1
            expression_parts.append(lines[index].strip())

        expressions.append(expression_parts)
        index += 1

    return expressions


def _starts_new_const_block(line: str) -> bool:
    stripped = line.strip()
    if not stripped or "const val " in stripped:
        return True
    if stripped.startswith("//"):
        return True
    if stripped.startswith(("private ", "internal ")):
        return True
    if stripped.startswith(("fun ", "data class ")):
        return True
    return stripped.startswith("}")
