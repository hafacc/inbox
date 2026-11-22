import BabyIcon from "@lucide/svelte/icons/baby";
import BellIcon from "@lucide/svelte/icons/bell";
import BookOpenIcon from "@lucide/svelte/icons/book-open";
import BriefcaseIcon from "@lucide/svelte/icons/briefcase";
import CalendarIcon from "@lucide/svelte/icons/calendar";
import CameraIcon from "@lucide/svelte/icons/camera";
import CarIcon from "@lucide/svelte/icons/car";
import CodeIcon from "@lucide/svelte/icons/code";
import CoffeeIcon from "@lucide/svelte/icons/coffee";
import CreditCardIcon from "@lucide/svelte/icons/credit-card";
import DumbbellIcon from "@lucide/svelte/icons/dumbbell";
import FileTextIcon from "@lucide/svelte/icons/file-text";
import FlagIcon from "@lucide/svelte/icons/flag";
import FolderIcon from "@lucide/svelte/icons/folder";
import Gamepad2Icon from "@lucide/svelte/icons/gamepad-2";
import GiftIcon from "@lucide/svelte/icons/gift";
import GlobeIcon from "@lucide/svelte/icons/globe";
import GraduationCapIcon from "@lucide/svelte/icons/graduation-cap";
import HeartIcon from "@lucide/svelte/icons/heart";
import HouseIcon from "@lucide/svelte/icons/house";
import InboxIcon from "@lucide/svelte/icons/inbox";
import InfoIcon from "@lucide/svelte/icons/info";
import LandmarkIcon from "@lucide/svelte/icons/landmark";
import LeafIcon from "@lucide/svelte/icons/leaf";
import LockIcon from "@lucide/svelte/icons/lock";
import MessageSquareIcon from "@lucide/svelte/icons/message-square";
import MusicIcon from "@lucide/svelte/icons/music";
import NewspaperIcon from "@lucide/svelte/icons/newspaper";
import PackageIcon from "@lucide/svelte/icons/package";
import PawPrintIcon from "@lucide/svelte/icons/paw-print";
import PlaneIcon from "@lucide/svelte/icons/plane";
import ReceiptIcon from "@lucide/svelte/icons/receipt";
import ShoppingBagIcon from "@lucide/svelte/icons/shopping-bag";
import SparklesIcon from "@lucide/svelte/icons/sparkles";
import StarIcon from "@lucide/svelte/icons/star";
import StethoscopeIcon from "@lucide/svelte/icons/stethoscope";
import TagIcon from "@lucide/svelte/icons/tag";
import TruckIcon from "@lucide/svelte/icons/truck";
import UsersIcon from "@lucide/svelte/icons/users";
import UtensilsIcon from "@lucide/svelte/icons/utensils";
import type { IconType } from "./icons";

// The lucide icon for each id in shared/themes/label-icons.json; label-icons.spec.ts keeps them in step.
/** Each label icon id's component. */
export const LABEL_ICON_COMPONENTS: Readonly<Record<string, IconType>> = {
  tag: TagIcon,
  users: UsersIcon,
  bell: BellIcon,
  "message-square": MessageSquareIcon,
  info: InfoIcon,
  inbox: InboxIcon,
  star: StarIcon,
  heart: HeartIcon,
  briefcase: BriefcaseIcon,
  home: HouseIcon,
  receipt: ReceiptIcon,
  "shopping-bag": ShoppingBagIcon,
  "credit-card": CreditCardIcon,
  landmark: LandmarkIcon,
  plane: PlaneIcon,
  car: CarIcon,
  truck: TruckIcon,
  package: PackageIcon,
  calendar: CalendarIcon,
  book: BookOpenIcon,
  "graduation-cap": GraduationCapIcon,
  code: CodeIcon,
  newspaper: NewspaperIcon,
  "file-text": FileTextIcon,
  folder: FolderIcon,
  music: MusicIcon,
  camera: CameraIcon,
  gift: GiftIcon,
  utensils: UtensilsIcon,
  coffee: CoffeeIcon,
  dumbbell: DumbbellIcon,
  stethoscope: StethoscopeIcon,
  gamepad: Gamepad2Icon,
  baby: BabyIcon,
  paw: PawPrintIcon,
  leaf: LeafIcon,
  globe: GlobeIcon,
  lock: LockIcon,
  flag: FlagIcon,
  sparkles: SparklesIcon,
};
