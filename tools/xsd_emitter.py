# -*- coding: utf-8 -*-
"""XSD emitter shared by the ON EBX custom modules (same F / T vocabulary as ebx-data-catalog's
extend_xsd.py, without the Companies patching code). Imported by build_xsd.py; never run directly."""

XS = {'string': 'xs:string', 'decimal': 'xs:decimal', 'int': 'xs:int', 'date': 'xs:date',
      'dateTime': 'xs:dateTime', 'boolean': 'xs:boolean', 'text': 'osd:text', 'id': 'id', 'resource': 'osd:resource',
      # EBX has no xs:long: big counters are xs:decimal, which is also what EBX SQL count() hands back.
      'long': 'xs:decimal', 'password': 'osd:password'}


def esc(s):
    return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')


class F(object):
    def __init__(self, name, type='string', en=None, fr=None, desc_en=None, desc_fr=None, fk=None,
                 pattern=None, enum=None, min=0, max=1, maxLen=None, children=None, assoc=None,
                 default=None, constraint=None, columns=None, hidden_ds=False, enumClass=None, uiBean=None, function=None):
        self.name, self.type, self.en = name, type, en or name
        self.fr = fr or self.en
        self.desc_en, self.desc_fr = desc_en, desc_fr or desc_en
        self.fk = fk            # "/root/Table" or (container, branch, tablePath)
        self.pattern, self.enum, self.min, self.max, self.maxLen = pattern, enum, min, max, maxLen
        self.children, self.assoc, self.default, self.constraint = children, assoc, default, constraint
        # EBX mapped tables refuse an unbounded string: every enumerated string gets a length facet.
        if self.enum is not None and self.maxLen is None and self.type == 'string':
            self.maxLen = 40
        self.columns = columns  # association tabular view columns
        self.enumClass = enumClass  # programmatic enumeration (osd:constraintEnumeration)
        self.uiBean = uiBean  # custom widget factory (osd:defaultView/widget osd:class)
        self.function = function  # computed value (osd:function class)
        self.hidden_ds = hidden_ds


class T(object):
    def __init__(self, name, en, fr, pk, fields, label=None, label_fr=None, desc_en=None, desc_fr=None, recordForm=None, trigger=None,
                 toolbars=None):
        self.name, self.en, self.fr, self.pk, self.fields = name, en, fr, pk, fields
        self.trigger = trigger  # osd:trigger class on the table
        self.label, self.label_fr, self.desc_en, self.desc_fr = label, label_fr, desc_en, desc_fr
        self.recordForm = recordForm  # osd:class of a UIForm rendering the record
        # dict of {'tabularViewTop': name, 'tabularViewRow': name, 'recordTop': name}, each optional; the
        # toolbar itself is a NAME reference into the module's <XsdBaseName>_toolbars.xml file, resolved by
        # EBX at render time - a wrong or missing name fails silently (no toolbar, no error), so
        # build_xsd.py cross-checks every name against that file before writing the XSD.
        self.toolbars = toolbars


def doc(ind, en, fr, desc_en=None, desc_fr=None):
    o = ['%s<xs:documentation xml:lang="en-US">' % ind, '%s    <osd:label>%s</osd:label>' % (ind, esc(en))]
    if desc_en:
        o.append('%s    <osd:description>%s</osd:description>' % (ind, esc(desc_en)))
    o += ['%s</xs:documentation>' % ind, '%s<xs:documentation xml:lang="fr-FR">' % ind,
          '%s    <osd:label>%s</osd:label>' % (ind, esc(fr))]
    if desc_fr:
        o.append('%s    <osd:description>%s</osd:description>' % (ind, esc(desc_fr)))
    o.append('%s</xs:documentation>' % ind)
    return o


def field(f, ind):
    o = []
    if f.assoc:
        occ = ' minOccurs="0" maxOccurs="0"'  # EBX: an association node must set both occurrences to 0
    else:
        occ = ' minOccurs="%d" maxOccurs="%s"' % (f.min, 'unbounded' if f.max == 0 else str(f.max))
    if f.default is not None:
        occ += ' default="%s"' % esc(str(f.default))
    inline = f.enum is not None or f.pattern is not None or f.maxLen is not None
    if f.children is not None or inline:
        o.append('%s<xs:element name="%s"%s>' % (ind, f.name, occ))
    else:
        o.append('%s<xs:element name="%s" type="%s"%s>' % (ind, f.name, XS[f.type], occ))
    i1 = ind + '    '
    o.append('%s<xs:annotation>' % i1)
    o += doc(i1 + '    ', f.en, f.fr, f.desc_en, f.desc_fr)
    app = []
    if f.fk or f.constraint or getattr(f, 'enumClass', None):
        app.append('<osd:otherFacets>')
        if f.fk:
            app.append('    <osd:tableRef>')
            if isinstance(f.fk, tuple):
                app.append('        <container>%s</container>' % f.fk[0])
                app.append('        <branch>%s</branch>' % f.fk[1])
                app.append('        <tablePath>%s</tablePath>' % f.fk[2])
            else:
                app.append('        <tablePath>%s</tablePath>' % f.fk)
            app.append('    </osd:tableRef>')
        if f.constraint:
            app.append('    <osd:constraint class="%s"/>' % f.constraint)
        if getattr(f, 'enumClass', None):
            app.append('    <osd:constraintEnumeration class="%s"/>' % f.enumClass)
        app.append('</osd:otherFacets>')
    if getattr(f, 'function', None):
        app.append('<osd:function class="%s"/>' % f.function)
    if getattr(f, 'encryptionClass', None):  # osd:password: reversible encryption instead of the default SHA-512 hash
        app += ['<osd:uiBean class="com.orchestranetworks.ui.UIPassword">', '    <encryptionClass>%s</encryptionClass>' % f.encryptionClass, '</osd:uiBean>']
    if getattr(f, 'uiBean', None):
        app += ['<osd:defaultView>', '    <widget osd:class="%s"/>' % f.uiBean, '</osd:defaultView>']  # UIWidgetFactory, as in the EBX add-on models (attribute osd:class)  # UIWidgetFactory: osd:defaultView/widget (osd:uiBean = legacy UIBeanEditor)
    if f.assoc:
        app += ['<osd:association>', '    <tableRefInverse>',
                '        <fieldToSource>%s</fieldToSource>' % f.assoc,
                '    </tableRefInverse>', '</osd:association>', '<osd:defaultView>']
        if f.hidden_ds:
            app.append('    <hiddenInDataServices>true</hiddenInDataServices>')
        app.append('    <displayMode>tab</displayMode>')
        if f.columns:
            app.append('    <tabularView>')
            for c in f.columns:
                app.append('        <column>%s</column>' % c)
            app.append('        <initialQueryEnabled>true</initialQueryEnabled>')
            app.append('    </tabularView>')
        app.append('</osd:defaultView>')
    if f.children is not None:
        app += ['<osd:defaultView>', '    <displayMode>expanded</displayMode>', '</osd:defaultView>']
    if app:
        o.append('%s    <xs:appinfo>' % i1)
        o += ['%s        %s' % (i1, a) for a in app]
        o.append('%s    </xs:appinfo>' % i1)
    o.append('%s</xs:annotation>' % i1)
    if f.children is not None:
        o += ['%s<xs:complexType>' % i1, '%s    <xs:sequence>' % i1]
        for c in f.children:
            o += field(c, i1 + '        ')
        o += ['%s    </xs:sequence>' % i1, '%s</xs:complexType>' % i1]
    elif inline:
        o += ['%s<xs:simpleType>' % i1, '%s    <xs:restriction base="%s">' % (i1, XS[f.type])]
        for v, le, lf in (f.enum or []):
            o += ['%s        <xs:enumeration value="%s">' % (i1, esc(v)), '%s            <xs:annotation>' % i1,
                  '%s                <xs:documentation xml:lang="en-US">%s</xs:documentation>' % (i1, esc(le)),
                  '%s                <xs:documentation xml:lang="fr-FR">%s</xs:documentation>' % (i1, esc(lf)),
                  '%s            </xs:annotation>' % i1, '%s        </xs:enumeration>' % i1]
        if f.maxLen:
            o.append('%s        <xs:maxLength value="%d"/>' % (i1, f.maxLen))
        if f.pattern:
            o.append('%s        <xs:pattern value="%s"/>' % (i1, esc(f.pattern)))
        o += ['%s    </xs:restriction>' % i1, '%s</xs:simpleType>' % i1]
    o.append('%s</xs:element>' % ind)
    return o


def table(t, ind):
    o = ['%s<xs:element name="%s" minOccurs="0" maxOccurs="unbounded">' % (ind, t.name)]
    i1 = ind + '    '
    o.append('%s<xs:annotation>' % i1)
    o += doc(i1 + '    ', t.en, t.fr, t.desc_en, t.desc_fr)
    o += ['%s    <xs:appinfo>' % i1, '%s        <osd:table>' % i1,
          '%s            <primaryKeys>%s</primaryKeys>' % (i1, ''.join('/%s ' % p for p in t.pk))]
    if getattr(t, 'recordForm', None):
        o.append('%s            <recordForm osd:class="%s"/>' % (i1, t.recordForm))
    if t.label:
        o.append('%s            <defaultLabel>%s</defaultLabel>' % (i1, esc(t.label)))
    if t.label_fr:
        o.append('%s            <defaultLabel xml:lang="fr-FR">%s</defaultLabel>' % (i1, esc(t.label_fr)))
    o.append('%s            <defaultView>' % i1)
    if getattr(t, 'toolbars', None):
        o.append('%s                <toolbars>' % i1)
        # element order matches the shipped DMA XSDs (e.g. ebx-ps-profiling-web/WEB-INF/ebx/schemas/
        # profiling_result.xsd): tabularViewTop, tabularViewRow, recordTop, then displayOfGroups/defaultViewForGroups.
        for tag in ('tabularViewTop', 'tabularViewRow', 'tileView', 'recordTop', 'hierarchyViewTop'):
            name = t.toolbars.get(tag)
            if name:
                o.append('%s                    <%s>%s</%s>' % (i1, tag, esc(name), tag))
        o.append('%s                </toolbars>' % i1)
    o += ['%s                <defaultViewForGroups>expanded</defaultViewForGroups>' % i1,
          '%s            </defaultView>' % i1, '%s        </osd:table>' % i1]
    if getattr(t, 'trigger', None):
        # a table trigger sits in xs:appinfo next to osd:table, never inside it (inside, 'trigger' is a text property of the table bean)
        o += ['%s        <osd:trigger class="%s">' % (i1, t.trigger), '%s            <enabled>true</enabled>' % i1, '%s        </osd:trigger>' % i1]
    o += ['%s    </xs:appinfo>' % i1, '%s</xs:annotation>' % i1, '%s<xs:complexType>' % i1, '%s    <xs:sequence>' % i1]
    for f in t.fields:
        o += field(f, i1 + '        ')
    o += ['%s    </xs:sequence>' % i1, '%s</xs:complexType>' % i1, '%s</xs:element>' % ind]
    return o

