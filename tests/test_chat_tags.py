"""Host-side behavior tests. Android rendering needs a real exteraLess client."""

import importlib.machinery
import importlib.util
import json
from pathlib import Path
import sys
import threading
import types
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
sdk = types.ModuleType("base_plugin")
sdk.BasePlugin = type("BasePlugin", (), {})
sdk.MenuItemData = types.SimpleNamespace
sdk.MenuItemType = types.SimpleNamespace(PROFILE_ACTION_MENU="PROFILE_ACTION_MENU")
loader = importlib.machinery.SourceFileLoader("chat_tags_tested", str(ROOT / "chat_tags.plugin"))
spec = importlib.util.spec_from_loader(loader.name, loader)
plugin = importlib.util.module_from_spec(spec)
with patch.dict(sys.modules, {"base_plugin": sdk}):
    loader.exec_module(plugin)


class IndexTests(unittest.TestCase):
    def setUp(self):
        self.index = plugin.TagIndex()
        self.index.set(100, -1060565714, "AltTG", plugin.parse_tags(
            "телеграм, telegram, альтернативные клиенты телеграм"))
        self.index.set(100, -7, "Рыболовы", ["рыбалка", "озеро"])
        self.index.set(200, -7, "Другой аккаунт", ["работа"])

    def test_phrases_are_preserved_and_duplicates_removed(self):
        self.assertEqual(plugin.parse_tags(
            "#телеграм,  telegram; TELEGRAM\n альтернативные   клиенты телеграм \n"),
            ["телеграм", "telegram", "альтернативные клиенты телеграм"])

    def test_cyrillic_and_latin_find_the_same_channel(self):
        for query in ("ТЕЛЕГРАМ", "  telegram  ", "альтернативные клиенты", "клиенты телеграм"):
            with self.subTest(query=query):
                self.assertEqual([m["did"] for m in self.index.matches(100, query)], [-1060565714])

    def test_tag_search_does_not_require_title_match(self):
        self.assertEqual(self.index.matches(100, "рыб")[0]["title"], "Рыболовы")
        self.assertEqual(self.index.matches(100, "озеро")[0]["did"], -7)

    def test_empty_query_has_no_suggestions(self):
        for query in ("", "  ", "#"):
            self.assertEqual(self.index.matches(100, query), [])
            self.assertEqual(self.index.suggestions(100, query), [])

    def test_accounts_are_isolated_by_user_id(self):
        self.assertEqual(self.index.matches(200, "рыбалка"), [])
        self.assertEqual(self.index.matches(100, "работа"), [])
        self.assertEqual(self.index.matches(999, "телеграм"), [])

    def test_round_trip_survives_restart(self):
        restored = plugin.TagIndex(json.loads(json.dumps(self.index.dump(), ensure_ascii=False)))
        self.assertEqual(restored.matches(100, "телеграм"), self.index.matches(100, "телеграм"))

    def test_removing_all_tags_removes_search_result(self):
        self.index.set(100, -7, "Рыболовы", [])
        self.assertEqual(self.index.matches(100, "рыбалка"), [])
        self.assertEqual(self.index.matches(200, "работа")[0]["did"], -7)

    def test_multiword_query_requires_every_word(self):
        self.assertEqual(self.index.matches(100, "клиенты рыбалка"), [])
        self.assertEqual(self.index.matches(100, "телеграм альтернативные")[0]["title"], "AltTG")

    def test_suggestions_are_unique_and_capped_at_two(self):
        self.index.set(100, -8, "Ещё один", ["телеграм", "телеграм боты", "телеграм новости"])
        tags = self.index.suggestions(100, "телег")
        self.assertEqual(len(tags), 2)
        self.assertEqual(len(set(tags)), 2)
        self.assertNotIn("телеграм", self.index.suggestions(100, "телег", ["ТЕЛЕГРАМ"]))
        self.assertEqual(len(self.index.matches(100, "телег", limit=2)), 2)

    def test_native_results_are_not_capped_at_two(self):
        for did in range(10, 20):
            self.index.set(100, -did, str(did), ["рыбалка"])
        self.assertEqual(len(self.index.matches(100, "рыбалка")), 11)

    def test_autocomplete_replaces_only_unfinished_tag(self):
        self.assertEqual(plugin.replace_last_tag("рыбалка, тел", "телеграм"), "рыбалка,телеграм\n")
        self.assertEqual(plugin.replace_last_tag("рыбалка\nтел", "телеграм"), "рыбалка\nтелеграм\n")

    def test_unicode_normalization(self):
        self.assertEqual(plugin.normalized("＃ＴＥＬＥＧＲＡＭ"), "telegram")
        self.assertEqual(plugin.normalized("озёро"), "озеро")
        self.assertEqual(plugin.clean_tag("теле\u200bграм"), "телеграм")

    def test_validation_reports_errors_instead_of_truncating(self):
        with self.assertRaises(ValueError):
            plugin.parse_tags("я" * 129)
        with self.assertRaises(ValueError):
            plugin.parse_tags(",".join(str(i) for i in range(65)))
        with self.assertRaises(ValueError):
            plugin.TagIndex({"version": 2, "accounts": {}})
        with self.assertRaises(ValueError):
            plugin.TagIndex({"version": 1, "accounts": {"100": {"-7": {"tags": "рыбалка"}}}})

    def test_returned_data_cannot_mutate_index(self):
        self.index.get(100, -7)["tags"].clear()
        self.index.dump()["accounts"].clear()
        self.assertEqual(self.index.get(100, -7)["tags"], ["рыбалка", "озеро"])

    def test_concurrent_reads_and_writes_are_consistent(self):
        errors = []

        def write():
            try:
                for i in range(100):
                    self.index.set(100, -7, "Рыболовы", ["рыбалка", str(i)])
            except Exception as error:
                errors.append(error)

        worker = threading.Thread(target=write)
        worker.start()
        for _ in range(100):
            plugin.TagIndex(self.index.dump())
            self.assertEqual(self.index.matches(100, "рыбалка")[0]["did"], -7)
        worker.join()
        self.assertEqual(errors, [])


class JavaList(list):
    """java.util.ArrayList as the client exposes it: get/size, no iteration."""

    def add(self, value):
        self.append(value)

    def get(self, index):
        return list.__getitem__(self, index)

    def size(self):
        return list.__len__(self)

    def __iter__(self):
        raise TypeError("'ArrayList' object is not iterable")


class TitleLimitTests(unittest.TestCase):
    def test_titles_keep_whole_words_count_and_ellipsis(self):
        self.assertEqual(plugin.shorten_title("Рыболовы", 0), "Рыболовы")
        self.assertEqual(plugin.shorten_title("Рыболовы", 10), "Рыболовы")
        self.assertEqual(plugin.shorten_title("Рыболовы Подмосковья", 10), "Рыболовы…")
        self.assertEqual(plugin.shorten_title("  Два   пробела  ", 30), "Два пробела")

    def test_unknown_setting_value_keeps_titles_whole(self):
        self.assertEqual(plugin.title_limit(None), 0)
        self.assertEqual(plugin.title_limit(99), 0)
        self.assertEqual(plugin.title_limit(4), 30)


class SearchLayoutTests(unittest.TestCase):
    def test_several_chats_share_a_row(self):
        self.assertEqual(plugin.pack_chat_rows([70, 80, 60, 70], 222, 6),
                         [[(0, 70), (1, 80), (2, 60)], [(3, 70)]])

    def test_expansion_keeps_every_chat_including_long_names(self):
        rows = plugin.pack_chat_rows([400, 400, 400, 80, 80], 200, 6)
        self.assertEqual([index for row in rows for index, width in row], list(range(5)))
        self.assertEqual(len(rows[:plugin.SEARCH_PREVIEW_ROWS]), 2)
        for row in rows:
            self.assertLessEqual(sum(width for _, width in row) + 6 * (len(row) - 1), 200)

    def test_width_change_reflows_all_chats(self):
        widths = [90, 90, 90]
        self.assertEqual(len(plugin.pack_chat_rows(widths, 190, 6)), 2)
        self.assertEqual(len(plugin.pack_chat_rows(widths, 90, 6)), 3)
        self.assertEqual(len(plugin.pack_chat_rows([], 0, 6)), 0)


class FakeView:
    """Small Android layout harness; title widths represent measured pixels."""
    def __init__(self, context=None, text="", width=0, height=0):
        self.text, self.width, self.height = text, width, height
        self.children, self.padding = [], (0, 0, 0, 0)
        self.params = self.LayoutParams(-1, -2)
        self.visibility, self.click = 0, None
        self.translation, self.top, self.position = 0.0, 0, 0
        self.scrolled_to = []

    @staticmethod
    def LayoutParams(width, height):
        return types.SimpleNamespace(width=width, height=height, topMargin=0, leftMargin=0)

    def __getattr__(self, name):
        if name.startswith("set") or name in ("scrollTo", "measure", "bringToFront"):
            return lambda *args: None
        raise AttributeError(name)

    def getContext(self): return None
    def getWidth(self): return self.width
    def getHeight(self): return self.height
    def getMeasuredWidth(self): return self.width or len(self.text) * 8 + self.padding[0] + self.padding[2]
    def getMeasuredHeight(self): return 24 + self.padding[1] + self.padding[3]
    def getPaddingLeft(self): return self.padding[0]
    def getPaddingTop(self): return self.padding[1]
    def getPaddingRight(self): return self.padding[2]
    def getPaddingBottom(self): return self.padding[3]
    def getLayoutParams(self): return self.params
    def setLayoutParams(self, params): self.params = params
    def setPadding(self, *values): self.padding = values[:4]
    def setVisibility(self, value): self.visibility = value
    def setText(self, value): self.text = value
    def setOnClickListener(self, fn): self.click = fn
    def removeAllViews(self): self.children.clear()
    def getChildCount(self): return len(self.children)
    def getChildAt(self, index): return self.children[index]
    def getChildAdapterPosition(self, child): return child.position
    def getLayoutManager(self):
        return types.SimpleNamespace(
            scrollToPositionWithOffset=lambda pos, offset: self.scrolled_to.append((pos, offset)))
    def getTop(self): return self.top
    def getTranslationY(self): return self.translation
    def setTranslationY(self, value): self.translation = value
    def addView(self, child, params):
        child.params = params
        self.children.append(child)


class SearchSuggestionsTests(unittest.TestCase):
    def setUp(self):
        self.instance = fake_plugin()
        self.instance._active = True
        self.instance.Linear = FakeView
        self.instance.AU = types.SimpleNamespace(displaySize=types.SimpleNamespace(x=220))
        self.instance.Theme = types.SimpleNamespace(getColor=lambda key: 0, key_windowBackgroundGray=0)
        self.instance.Ellipsize = types.SimpleNamespace(END=0)
        self.instance._background = lambda color: None
        self.instance._text = lambda context, text, *args: FakeView(text=text)
        self.settings = {plugin.AVATARS_KEY: False}
        self.instance.get_setting = lambda key, default: self.settings.get(key, default)
        pager = types.SimpleNamespace(
            searchContainer=FakeView(width=220, height=500),
            searchListView=FakeView(), emptyView=FakeView(),
            pagesPaddingTop=10, pagesPaddingBottom=20,
            dialogsSearchAdapter=types.SimpleNamespace(delegate=None),
        )
        root = FakeView()
        root.setPadding(12, 4, 12, 4)
        self.state = {"pager": pager, "root": root, "rows": FakeView(),
                      "scroll": FakeView(), "toggle": FakeView(), "height": 0,
                      "expanded": False, "identity": None, "size": None,
                      "query": "#самокаты", "account": 0, "uid": 100,
                      "parent": types.SimpleNamespace(getCurrentAccount=lambda: 0),
                      "matches": [{"did": -n, "title": "Очень длинное название " + str(n),
                                   "tag": "Самокаты"} for n in range(1, 4)]}
        self.sdk = types.ModuleType("android_utils")
        self.sdk.OnClickListener = lambda fn: fn
        self.patch = patch.dict(sys.modules, {"android_utils": self.sdk})
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def render(self): self.instance._render_suggestions(self.state)

    def test_preview_and_expansion_open_third_chat_with_title_only(self):
        opened = []
        self.instance._open_dialog = lambda state, account, uid, did: opened.append(did)
        self.render()
        self.assertEqual(len(self.state["rows"].children), 2)
        self.assertIn("(3)", self.state["toggle"].text)
        self.instance._toggle_suggestions(self.state)
        self.assertEqual(len(self.state["rows"].children), 3)
        third = self.state["rows"].children[2].children[0]
        self.assertEqual(third.text, self.state["matches"][2]["title"])
        self.assertNotIn("#Самокаты", third.text)
        third.click(None)
        self.assertEqual(opened, [-3])
        self.instance._toggle_suggestions(self.state)
        self.assertEqual(len(self.state["rows"].children), 2)

    def test_avatar_precedes_title_and_can_be_disabled(self):
        self.settings[plugin.AVATARS_KEY] = True
        self.instance._avatar = lambda context, match, account: FakeView(width=22)
        self.render()
        chip = self.state["rows"].children[0].children[0]
        self.assertEqual(len(chip.children), 2)
        self.assertEqual(chip.children[0].width, 22)
        self.assertEqual(chip.children[1].text, self.state["matches"][0]["title"])
        self.settings[plugin.AVATARS_KEY] = False
        self.render()
        self.assertEqual(self.state["rows"].children[0].children[0].children, [])

    def test_buttons_scroll_away_with_the_first_result(self):
        self.render()
        view = self.state["pager"].searchListView
        view.padding = (0, 100, 0, 0)
        first = FakeView()
        view.children.append(first)
        first.top = 100
        self.instance._sync_search_scroll(self.state)
        self.assertEqual(self.state["root"].translation, 0)
        first.top = 40
        self.instance._sync_search_scroll(self.state)
        self.assertEqual(self.state["root"].translation, -60)
        first.position = 3
        self.instance._sync_search_scroll(self.state)
        self.assertLess(self.state["root"].translation, -self.state["height"])

    def test_expanding_keeps_first_result_below_the_buttons(self):
        self.render()
        view = self.state["pager"].searchListView
        first = FakeView()
        first.top = view.getPaddingTop() - 15  # scrolled 15 px under the buttons
        view.children.append(first)
        self.instance._toggle_suggestions(self.state)
        self.assertEqual(view.scrolled_to[-1], (0, -15))

    def test_long_titles_are_cut_by_the_setting(self):
        self.settings[plugin.TITLE_LIMIT_KEY] = 2  # 15 characters
        self.render()
        chip = self.state["rows"].children[0].children[0]
        self.assertEqual(chip.text, "Очень длинное…")
        self.assertLessEqual(len(chip.text), 15)

    def test_three_short_names_fit_and_need_no_expand_button(self):
        for n, match in enumerate(self.state["matches"]):
            match["title"] = "Чат" + str(n)
        self.render()
        self.assertEqual(sum(len(row.children) for row in self.state["rows"].children), 3)
        self.assertEqual(self.state["toggle"].visibility, 8)

    def test_expanded_list_scrolls_and_restores_native_padding_on_empty_query(self):
        self.state["matches"] *= 20
        self.state["expanded"] = True
        self.render()
        self.assertEqual(sum(len(row.children) for row in self.state["rows"].children), 60)
        self.assertLess(self.state["height"], 250)
        view = self.state["pager"].searchListView
        self.assertEqual(view.getPaddingTop(), 10 + self.state["height"])
        self.assertEqual(view.getPaddingBottom(), 20)
        self.state["matches"] = []
        self.render()
        self.assertEqual(view.getPaddingTop(), 10)
        self.assertFalse(self.state["expanded"])

    def test_new_query_collapses_expansion_and_retains_all_matches(self):
        for match in self.state["matches"]:
            self.instance._index.set(100, match["did"], match["title"], ["самокаты"])
        self.instance._update_suggestions(self.state)
        self.state["expanded"] = True
        self.state["query"] = "самок"
        self.instance._update_suggestions(self.state)
        self.assertFalse(self.state["expanded"])
        self.assertEqual(len(self.state["matches"]), 3)


class User:
    def __init__(self, ident, name="Человек", deleted=False):
        self.id, self.name, self.deleted = ident, name, deleted


class Chat:
    def __init__(self, ident, title="Рыболовы"):
        self.id, self.title = ident, title


class Encrypted:
    def __init__(self, ident, user_id):
        self.id, self.user_id = ident, user_id


class Controller:
    def __init__(self):
        self.users, self.chats, self.encrypted = {}, {}, {}

    def getUser(self, ident):
        return self.users.get(ident)

    def getChat(self, ident):
        return self.chats.get(ident)

    def getEncryptedChat(self, ident):
        return self.encrypted.get(ident)

    def putEncryptedChat(self, obj, cached):
        self.encrypted[obj.id] = obj


class Storage(Controller):
    currentAccount = 0

    def getChatSync(self, ident):
        raise AssertionError("Must not block its own storage queue")

    def getUserSync(self, ident):
        raise AssertionError("Must not block its own storage queue")


class HookParam:
    def __init__(self, instance, args, result=0):
        self.thisObject, self.args, self.result = instance, args, result

    def getThrowable(self):
        return None

    def getResult(self):
        return self.result

    def setResult(self, value):
        self.result = value


def fake_plugin():
    instance = plugin.ChatTagsPlugin()
    instance._index = plugin.TagIndex()
    instance._get = lambda obj, name: getattr(obj, name, None)
    instance._uid = lambda account: 100 if account == 0 else 200
    instance._jlong = int
    instance._jint = int
    instance._dp = lambda value: value
    instance.TLUser, instance.TLChat, instance.TLEncrypted = User, Chat, Encrypted
    instance.JString = str
    instance.UserObject = types.SimpleNamespace(getUserName=lambda user: user.name)
    instance.DialogObject = types.SimpleNamespace(
        isEncryptedDialog=lambda did: did > 0 and bool(did & 0x4000000000000000),
        getEncryptedChatId=lambda did: did & 0xffffffff,
    )
    controller = Controller()
    instance.Controller = types.SimpleNamespace(getInstance=lambda account: controller)
    instance._test_controller = controller
    return instance


class HookBehaviorTests(unittest.TestCase):
    def setUp(self):
        self.instance = fake_plugin()
        self.storage = Storage()
        self.storage.chats[7] = Chat(7)
        self.instance._index.set(100, -7, "Рыболовы", ["рыбалка"])

    def test_profile_reserves_space_once(self):
        profile = types.SimpleNamespace(getDialogId=lambda: -7)
        param = HookParam(profile, [True], result=74)
        self.instance._profile_height(param)
        self.assertEqual(param.result, 74 + plugin.PROFILE_HEIGHT_DP)
        empty = HookParam(types.SimpleNamespace(getDialogId=lambda: 0), [True], result=74)
        self.instance._profile_height(empty)
        self.assertEqual(empty.result, 74)

    def test_save_failure_keeps_previous_index(self):
        self.instance._active = True
        self.instance._profile_title = lambda state: "Рыболовы"
        self.instance.set_setting = lambda key, value: None
        self.instance.get_setting = lambda key, default: self.instance._index.dump()
        with self.assertRaises(ValueError):
            self.instance._save_tags({"account": 0, "uid": 100, "did": -7}, "работа")
        self.assertEqual(self.instance._index.get(100, -7)["tags"], ["рыбалка"])

    def test_account_changed_while_editor_open_blocks_save(self):
        self.instance._active = True
        self.instance._uid = lambda account: 200
        with self.assertRaises(ValueError):
            self.instance._save_tags({"account": 0, "uid": 100, "did": -7}, "работа")

    def test_successful_save_survives_new_index(self):
        self.instance._active = True
        self.instance._profile_title = lambda state: "Рыболовы"
        settings = {}
        self.instance.set_setting = lambda key, value: settings.update({key: value})
        self.instance.get_setting = lambda key, default: settings.get(key, default)
        self.instance._profiles, self.instance._searches = {}, {}
        self.instance._save_tags({"account": 0, "uid": 100, "did": -7}, "работа")
        restored = plugin.TagIndex(settings[plugin.STORE_KEY])
        self.assertEqual(restored.get(100, -7)["tags"], ["работа"])

    def test_first_tag_can_be_saved_without_a_profile_block(self):
        self.instance._index = plugin.TagIndex()
        self.instance._active = True
        self.instance._profile_title = lambda state: "AltTG"
        settings = {}
        self.instance.set_setting = lambda key, value: settings.update({key: value})
        self.instance.get_setting = lambda key, default: settings.get(key, default)
        self.instance._profiles, self.instance._searches = {}, {}
        self.instance._save_tags({"account": 0, "uid": 100, "did": -1060565714}, "телеграм")
        self.assertEqual(plugin.TagIndex(settings[plugin.STORE_KEY]).matches(100, "телеграм")[0]["title"], "AltTG")

    def test_menu_opens_first_tag_editor_without_an_attached_block(self):
        self.instance._index = plugin.TagIndex()
        self.instance._active = True
        opened = []
        self.instance._edit_tags = opened.append
        profile = types.SimpleNamespace(
            getCurrentAccount=lambda: 0,
            getDialogId=lambda: -1060565714,
            getParentActivity=lambda: "context",
        )
        self.instance._on_profile_menu({"fragment": profile})
        self.assertEqual(len(opened), 1)
        self.assertNotIn("root", opened[0])
        self.assertEqual(self.instance._index.get(opened[0]["uid"], opened[0]["did"])["tags"], [])

    def test_hook_denial_keeps_menu_editor_available(self):
        self.instance.get_setting = lambda key, default: default
        self.instance._init_android = lambda: None
        registered = []
        self.instance.add_menu_item = lambda item: registered.append(item) or item.item_id
        self.instance.log = lambda text: None

        def deny_hooks():
            raise RuntimeError("hooks отказ")

        self.instance._register_hooks = deny_hooks
        queued = []
        client_utils = types.ModuleType("client_utils")
        client_utils.run_on_queue = queued.append
        with patch.dict(sys.modules, {"client_utils": client_utils}):
            self.instance.on_plugin_load()
        # Startup work is queued off the main thread.
        self.assertEqual(len(queued), 1)
        self.assertEqual(registered, [])
        queued[0]()
        self.assertTrue(self.instance._active)
        self.assertFalse(self.instance._hooks_ready)
        self.assertEqual(len(registered), 1)
        self.assertEqual(registered[0].menu_type, "PROFILE_ACTION_MENU")
        self.assertTrue(self.instance._status_error)
        self.assertIn("hooks отказ", self.instance._status_detail)
        opened = []
        self.instance._edit_tags = opened.append
        registered[0].on_click({"fragment": types.SimpleNamespace(
            getCurrentAccount=lambda: 0, getDialogId=lambda: -7, getParentActivity=lambda: "context")})
        self.assertEqual(len(opened), 1)

    def test_unload_before_background_start_skips_registration(self):
        self.instance.get_setting = lambda key, default: default
        started = []
        self.instance._init_android = lambda: started.append("init")
        queued = []
        client_utils = types.ModuleType("client_utils")
        client_utils.run_on_queue = queued.append
        android_utils = types.ModuleType("android_utils")
        android_utils.run_on_ui_thread = lambda fn: fn()
        with patch.dict(sys.modules, {"client_utils": client_utils, "android_utils": android_utils}):
            self.instance.on_plugin_load()
            self.instance.on_plugin_unload()
            queued[0]()
        self.assertEqual(started, [])

    def test_android_initialization_respects_denied_engine_classes(self):
        requested = []
        def jclass(name):
            requested.append(name)
            if name.startswith("app.exteraless.plugins."):
                raise RuntimeError("internal engine classes are unavailable to plugins")
            return type("JavaClass", (), {})

        java = types.ModuleType("java")
        java.jclass, java.dynamic_proxy, java.jlong, java.jint = jclass, lambda cls: object, int, int
        reflection = types.ModuleType("hook_utils")
        reflection.get_private_field = lambda obj, name: None
        reflection.set_private_field = lambda obj, name, value: True
        with patch.dict(sys.modules, {"java": java, "hook_utils": reflection}):
            self.instance._init_android()
        self.assertNotIn("app.exteraless.plugins.PluginServices", requested)
        self.assertNotIn("org.telegram.messenger.MessagesStorage", requested)

    def test_clicking_suggestion_loads_on_sdk_worker_then_posts_to_ui(self):
        self.instance._active = True
        self.instance.Storage = types.SimpleNamespace(getInstance=lambda account: self.storage)
        reads, workers, ui = [], [], []
        self.storage.getChatSync = lambda ident: reads.append(ident) or self.storage.getChat(ident)
        client_utils = types.ModuleType("client_utils")
        client_utils.run_on_queue = lambda fn, queue: workers.append((fn, queue))
        android_utils = types.ModuleType("android_utils")
        android_utils.run_on_ui_thread = ui.append
        with patch.dict(sys.modules, {"client_utils": client_utils, "android_utils": android_utils}):
            self.instance._open_dialog({"parent": object()}, 0, 100, -7)
            self.assertEqual(reads, [])
            self.assertEqual(workers[0][1], "chat_tags_open")
            workers[0][0]()
            self.assertEqual(reads, [7])
            self.assertEqual(len(ui), 1)


if __name__ == "__main__":
    unittest.main()
