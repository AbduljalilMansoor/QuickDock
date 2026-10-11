package com.quickdock.app;

/** ودجت موضوع على شبكة شاشة العدادات: العمود/الصف + عدد الأعمدة/الصفوف التي يشغلها. */
final class DeckItem {
    String type;
    int col, row, cs, rs;

    DeckItem(String type, int col, int row, int cs, int rs) {
        this.type = type; this.col = col; this.row = row; this.cs = cs; this.rs = rs;
    }

    DeckItem copy() { return new DeckItem(type, col, row, cs, rs); }
}
