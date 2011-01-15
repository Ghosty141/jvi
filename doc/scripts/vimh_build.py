import re
import cgi
import xml.etree.ElementTree as ET
import urllib
import vimh_scan as vs
from StringIO import StringIO

# accept tokens from vim help scanner
#
# A builder is created with a set of tags.
# It puts stuff together per file
#

# The following are the tokens recognized by the vim scanner.
# They are grouped by either paragraphs or words, where paragraphs
# are groups of lines.
#
# Note: there is a link type of 'hidden', much like a token
#

SET_PRE   = set(('header',
                 'ruler',
                 'graphic',
                 'section',
                 'title',
                 'example'))

SET_WORD  = set(('pipe',
                 'star',
                 'opt',
                 'ctrl',
                 'special',
                 'note',
                 'url',
                 'word',
                 'chars'))

SET_NL    = set(('newline',
                 'blankline'))

SET_OTHER = set(('eof'))

TY_PRE  = 1
TY_WORD = 2
TY_NL   = 3
TY_EOF  = 4

MAP_TY = {}
MAP_TY.update(zip(SET_PRE,  (TY_PRE,) * len(SET_PRE)))
MAP_TY.update(zip(SET_WORD, (TY_WORD,) * len(SET_WORD)))
MAP_TY.update(zip(SET_NL,   (TY_NL,)   * len(SET_NL)))
MAP_TY['eof'] = TY_EOF

def build_link_re_from_pat():
    global RE_LINKWORD
    RE_LINKWORD = re.compile(
            vs.PAT_OPTWORD  + '|' + \
            vs.PAT_CTRL     + '|' + \
            vs.PAT_SPECIAL)

#
# This class is a base class for accepting tokens from VimHelpScanner.
#
class VimHelpBuildBase(object):

    def start_file(self, filename):
        self.filename = filename
        self.out = [ ]

    def start_line(self, lnum, input_line):
        """The next line to be parsed, generally for debug/diagnostics"""
        self.input_line = input_line
        self.lnum = lnum

    def markup(self, markup):
        pass

    def put_token(self, token_data):
        """token_data is (token, chars, col)."""
        ###print token_data
        pass

    def get_output(self):
        return self.out

    def error(self, info):
        print "%s at %s:%d '%s'" \
                % (info, self.filename, self.lnum, self.input_line)

RE_TAGLINE = re.compile(r'(\S+)\s+(\S+)')

class Link:
    def __init__(self, filename):
        self.filename = filename

class Links(dict):
    def __missing__(self, key):
        return None

    def __init__(self, tags):
        for line in tags:
            m = RE_TAGLINE.match(line)
            if m:
                vim_tag, filename = m.group(1, 2)
                self.do_add_tag(filename, vim_tag)

    def do_add_tag(self, filename, vim_tag):
        # determine style for a plain link
        style = 'hidden'
        m = RE_LINKWORD.match(vim_tag)
        if m:
            # style to one of: opt, ctrl, special
            style = m.lastgroup
        link = Link(filename)
        link.style = style
        self[vim_tag] = link
        return link

    def maplink(self, vim_tag, style = None):
        link = self[vim_tag]
        if link is not None:
            # this is a known link from the tags file
            if style and style != link.style and style != 'pipe':
                print 'LINK STYLE MISMATCH'
            pass
        elif style is not None:
            # not a known link, but a style was specified
            pass
        else:
            # not know link, no class specifed, just return it
            return vim_tag

#
# XML builder
#
# <vimhelp> is the root, the schema looks a bit like
#       <vimhelp> ::= [ <p> | <pre> | <table> ]+
# and these are made up of leaf elements and character data
#       element   ::= <target> | <link> | <em>
#
# Every element can have a 't' attribute (type). For <pre> they are
# one of SET_PRE. For the leaf elements they are from SET_WORD.
# Note that 'word' and 'chars' is typically just text/cdata.
#
# All '\n' that are encountered are copied into <p> and <pre> elements. And for
# <table> elements, each column gets a '\n' copied into it. This is done so
# that the original text *and* intent can be recreated from the xml
# representation.  For example, when expressing a table, multiple words on
# the same line and in the same column, may be handled together.
#
# <p> elements
#       These elements group words and chars, including <em> elements from
#       SET_WORD.  There is typically no 't' attribute associated with a <p>.
#       A <p> is terminated by a blank line; but note that any number of
#       trailing \n are copied into the <p>'s text.
#
# <pre> elements
#       These elements are typically scanned as complete lines.
#       Multiple <pre> of the same 't' (type) are combined into a single <pre>
#       element.
#
# <table> elements are introduced by markup such as:
#       #*# table:form=index:id=xxx 1:tag 17:command 33:opt:note 36:desc #*#
#       #*# table 1 17 33 36 #*#
#   - where the first word must be table and any with it anything like
#     id=xxx becomes an attribute on the table. Known attributes are
#               form ::= index  // an index of commands, the first and second
#                               // columns may be combined into a single column
#                               // when an output table is generated. The
#                               // "command" column is displayed as a
#                               // link to "tag" column's target.
#                      | ref    // typically holds descriptions for a commands.
#                               // Each description is the target of a link.
#                      | simple // just a table
#     There is no requirement to use this "form" attribute/values, but
#     that's what I'm using to markup my vim help files for processing...
#   - the rest of the groups are the columns. The first item in the group
#     is the column where the table starts. The rest of the items 
#

def make_elem(elem_tag, style = None, chars = '', parent = None):
    if isinstance(style, str):
        style = {'t':style}
    elif style is None:
        style = {}
    e = ET.Element(elem_tag, style)
    e.text = chars
    e.tail = ''
    if parent is not None:
        parent.append(e)
    return e

def make_sub_elem(parent, elem_tag, style = None, chars = ''):
    return make_elem(elem_tag, style, chars, parent)

def elem_text(e):
    sb = StringIO()
    internal_elem_text(e, sb)
    return sb.getvalue()

def internal_elem_text(e, sb):
    sb.write(e.text)
    for i in e.getchildren():
        internal_elem_text(i, sb)
    sb.write(e.tail)

def dump_table(table):
    print 'table:'
    for tr in table:
        print '  tr:'
        for td in tr:
            text = elem_text(td)
            l = [x.get('t') for x in td]
            s = set(l)
            print '    td:', re.sub('\n', r'\\n', text)
            print '      :', s, l

class XmlLinks(Links):

    def do_add_tag(self, filename, vim_tag):
        link = super(XmlLinks, self).do_add_tag(filename, vim_tag)
        print 'do_add_tag:', vim_tag, link.filename, link.style

    def maplink(self, vim_tag, style = None):
        link = self[vim_tag]
        if link is not None:
            # this is a known link from the tags file
            if style and style != link.style and style != 'pipe':
                print 'LINK STYLE MISMATCH'
            style = {'t':style, 'filename':link.filename}
            elem_tag = 'link'
        elif style is not None:
            # not a known link, but a style was specified
            elem_tag = 'em'
        else:
            # not known link, no class specifed
            return vim_tag
        print "maplink: '%s' '%s' '%s'" % (vim_tag, elem_tag, style)
        return make_elem(elem_tag, style, vim_tag)

class VimHelpBuildXml(VimHelpBuildBase):

    def __init__(self, tags):
        build_link_re_from_pat()
        self.links = XmlLinks(tags)
        self.blank_lines = 0
        self.root = ET.Element('vimhelp')
        self.tree = ET.ElementTree(self.root)
        self.cur_elem = None
        self.cur_table = None
        self.after_blank_line = False

    def start_file(self, filename):
        super(VimHelpBuildXml, self).start_file(filename)
        self.root.set('filename', filename)

    def get_output(self):
        return self.tree

    def start_line(self, lnum, line):
        super(VimHelpBuildXml, self).start_line(lnum, line)
        ### print 'start_line:', self.lnum, self.input_line

    def markup(self, markup):
        markup = markup.strip()
        print 'markup:', markup
        cmd,rest = markup.split(None,1)
        started = False
        if cmd.find('table') >= 0:
            started = self.check_start_table(cmd, rest)

        if not started:
            self.error('UNKNOWN MARKUP COMMAND ' + cmd)
        pass

    def put_token(self, token_data):
        """token_data is (token, chars, col)."""
        token, chars, col = token_data
        ty = MAP_TY[token]
        ### print 'token_data:', ty, token_data

        if self.cur_table is not None:
            ret = self.check_stop_table(ty, token_data)
            if ret:
                return

        if ty == TY_NL:
            if token == 'blankline':
                self.after_blank_line = True
            self.add_stuff('\n', token_data)
        elif ty == TY_PRE:
            self.add_para(token, chars)
        elif ty == TY_EOF:
            pass
        else:
            if self.after_blank_line:
                self.cur_elem = None
                self.after_blank_line = False
            if token == 'chars':
                w = chars
            elif token == 'word':
                # may end up mapped to a 'link' or 'em'
                w = self.links.maplink(chars)
            elif token == 'pipe':
                w = self.links.maplink(chars, 'pipe')
            elif token == 'star':
                w = make_elem('target', token, chars)
            elif token in ('opt', 'ctrl', 'special'):
                w = self.links.maplink(chars, token)
            else:
                w = make_elem('em', token, chars)

            self.add_stuff(w, token_data)

    ##
    # Add paragraphs and contents of a particular type.
    # Consecutive stuff of the same token type are put into the
    # same paragraph.
    def add_para(self, token, chars):
        e = self.get_cur_elem('pre', token)
        self.do_add_stuff(chars, e)

    def add_stuff(self, stuff, token_data):
        if self.cur_table is not None:
            self.add_to_table(stuff, token_data)
            return

        # newlines can be added to any type of element
        e = self.cur_elem if TY_NL == MAP_TY[token_data[0]] else None

        self.do_add_stuff(stuff, e)

    def do_add_stuff(self, stuff, e = None):
        """Add plain text or an element to current paragraph."""
        if e is None:
            e = self.get_cur_elem()
        if ET.iselement(stuff):
            e.append(stuff)
            return
        if len(e) == 0:
            e.text += stuff
        else:
            e[-1].tail += stuff

    ##
    # Get the current element of the specified tag-style.
    # If the current element doesn't match then create a new one.
    # 
    # @return the current elemenent
    def get_cur_elem(self, elem_tag = 'p', style = None):
        if self.cur_elem is not None:
            if self.cur_elem.tag == elem_tag \
                    and self.cur_elem.get('t') == style:
                return self.cur_elem
            self.cur_elem = None
        e = make_sub_elem(self.root, elem_tag, style)
        self.cur_elem = e
        return e

    def check_start_table(self, cmd, column_info):
        t01 = cmd.split(':')
        if 'table' != t01[0]:
            return False
        self.t_args = t01
        self.t_data = []

        # convert info to list of list items: col# , 'arg2', 'arg3', ...
        t02 = [x.split(':') for x in  column_info.split()]
        self.t_cols = [ [int(x[0]),] + x[1:] for x in t02 ]

        self.cur_elem = None
        self.cur_table = self.get_cur_elem('table')
        self.cur_elem = None
        for k,v in [ x.split('=') for x in t01[1:] if x.find('=') >= 0 ]:
            self.cur_table.set(k, v)
        self.cur_table.set('markup', cmd + ' ' + column_info)
        return True

    def check_stop_table(self, ty, token_data):
        do_build = False
        if ty in (TY_PRE, TY_EOF):
            do_build = True
        elif token_data[0] == 'blankline':
            do_build = True

        if do_build:
            self.build_table()
            dump_table(self.cur_table)
            self.cur_table = None
            self.t_data = None
        return do_build

    def add_to_table(self, w, token_data):
        self.t_data.append((token_data[0], w, token_data[2]))

    def build_table(self):
        cpos = [ x[0]-1 for x in self.t_cols]
        print 'XXX', cpos

        tr = None
        for token, stuff, pos in self.t_data:
            if pos == 0 and (not isinstance(stuff, str) or not stuff.isspace())\
                    or tr is None:
                if tr is not None:
                    self.cur_table.append(tr)
                tr = make_elem('tr')
                td = [ make_sub_elem(tr, 'td') for x in xrange(len(cpos))]
            if MAP_TY[token] == TY_NL:
                for x in td:
                    self.do_add_stuff('\n', x)
            else:
                col = len(cpos) - 1 # assume words in last col
                for i in xrange(len(cpos) - 1):
                    if cpos[i] <= pos < cpos[i+1]:
                        col = i
                        break
                self.do_add_stuff(stuff, td[col])
        if tr is not None: self.cur_table.append(tr)




###################################################################

#
# Simple Html builder, should reproduce original work from Carlo
#

# Note that the lazy creation of link_plain,link_pipe 
# provides for reporting defined links that are not referenced
class HtmlLinks(Links):
    # styles map to the html style class for the link
    styles = dict(link='l', opt='o', ctrl='k',
                  special='s', hidden='d')

    def __init__(self, tags):
        super(HtmlLinks, self).__init__(tags)

    def get_tag(self, vim_tag):
        """Lazily create link_plain and link_pipe."""
        link = self[vim_tag]
        if not link : return None
        if not hasattr(link, 'link_plain'):
            part1 = '<a href="' + link.filename + '.html#' + \
                    urllib.quote_plus(vim_tag) + '"'
            part2 = '>' + cgi.escape(vim_tag) + '</a>'
            link.link_pipe = part1 \
                    + ' class="' + self.styles['link'] + '"' + part2
            link.link_plain = part1 \
                    + ' class="' + self.styles[link.style] + '"' + part2
        return link

    def maplink(self, vim_tag, css_class = None):
        link = self.get_tag(vim_tag)
        if link is not None:
            # this is a known link from the tags file
            if css_class == 'link':
                # drop the anchor if foo.txt and foo.txt.html#foo.txt
                if vim_tag.endswith('.txt') \
                        and link.link_pipe.find(
                                '"' + vim_tag + '.html#' + vim_tag + '"') >= 0:
                    return link.link_pipe.replace('#' + vim_tag, '');
                return link.link_pipe
            else: return link.link_plain
        elif css_class is not None:
            # not a known link, but a class was specified
            return '<span class="' + self.styles[css_class] \
                    + '">' + cgi.escape(vim_tag) + '</span>'
        else:
            # not know link, no class specifed, just return it
            return cgi.escape(vim_tag)


class VimHelpBuildHtml(VimHelpBuildBase):

    def __init__(self, tags):
        build_link_re_from_pat()
        self.links = HtmlLinks(tags)


    def markup(self, markup):
        markup = markup.strip()
        ### print 'markup: %s:%s "%s"' \
        ###         % (self.filename, self.lnum, markup)
        pass

    def put_token(self, token_data):
        """token_data is (type, chars, col)."""
        token, chars, col = token_data
        ###print token_data
        if 'pipe' == token:
            self.out.append(self.links.maplink(chars, 'link'))
        elif 'star' == token:
            vim_tag = chars
            self.out.append('<a name="' + urllib.quote_plus(vim_tag) +
                    '" class="t">' + cgi.escape(vim_tag) + '</a>')
        elif 'opt' == token:
            self.out.append(self.links.maplink(chars, 'opt'))
        elif 'ctrl' == token:
            self.out.append(self.links.maplink(chars, 'ctrl'))
        elif 'special' == token:
            self.out.append(self.links.maplink(chars, 'special'))
        elif 'title' == token:
            self.out.append('<span class="i">' +
                    cgi.escape(chars) + '</span>')
        elif 'note' == token:
            self.out.append('<span class="n">' +
                    cgi.escape(chars) + '</span>')
        elif 'ruler' == token:
            self.out.append('<span class="h">' + chars + '</span>')
        elif 'header' == token:
            self.out.append('<span class="h">' +
                    cgi.escape(chars) + '</span>')
        elif 'graphic' == token:
            self.out.append(cgi.escape(chars))
        elif 'url' == token:
            self.out.append('<a class="u" href="' + chars + '">' +
                    cgi.escape(chars) + '</a>')
        elif 'word' == token:
            self.out.append(self.links.maplink(chars))
        elif 'example' == token:
            self.out.append('<span class="e">' + cgi.escape(chars) +
                    '</span>\n')
        elif 'section' == token:
            # NOTE: WHY NOT cgi.escape?????
            self.out.append(r'<span class="c">' + chars + '</span>')
            ### print self.filename + ': section: "' + chars +'"'
        elif 'chars' == token:
            if not chars.isspace():
                ###print '"%s" %s:"%s" NOT ISSPACE' \
                ###        % (chars,self.filename, self.input_line)
                ### the only non-space I've seen is blanks followed by a double-quote
                pass
            self.out.append(cgi.escape(chars))
        elif token in ('newline', 'blankline'):
            self.out.append('\n')
        elif 'eof' == token:
            pass
        else: print 'ERROR: unknown token "' + token + '"'
